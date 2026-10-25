package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.campaign.entity.Campaign
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignWindow
import com.kiva.domain.aggregate.campaign.repository.CampaignOverlapException
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.CampaignTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class CampaignRepositoryImpl : CampaignRepository {

    override fun nextId(): CampaignId =
        TransactionManager.current().exec("SELECT nextval('${CampaignTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            CampaignId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${CampaignTbl.ID_SEQUENCE}') returned nothing")

    // The key is a parameter, as for every advisory lock (it never shows in the SQL log).
    override fun lockAll() {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", listOf(TextColumnType() to LOCK_KEY)) { }
    }

    override fun find(id: CampaignId): Campaign? = CampaignTbl.selectAll().where { CampaignTbl.campaignId eq id.value }.singleOrNull()?.toCampaign()

    override fun findAll(): List<Campaign> =
        CampaignTbl.selectAll().orderBy(CampaignTbl.startsAt to SortOrder.DESC, CampaignTbl.campaignId to SortOrder.DESC).map { it.toCampaign() }

    override fun findBySlug(slug: Slug): Campaign? = CampaignTbl.selectAll().where { CampaignTbl.slug eq slug.value }.singleOrNull()?.toCampaign()

    // [a, b) and [c, d) share a moment when a < d and c < b: the same as the EXCLUDE constraint's &&.
    override fun findActiveOverlapping(window: CampaignWindow, except: CampaignId?): Campaign? =
        CampaignTbl.selectAll().where {
            val overlapping = (CampaignTbl.isActive eq true) and (CampaignTbl.startsAt less window.endsAt.utc()) and (CampaignTbl.endsAt greater window.startsAt.utc())
            if (except == null) overlapping else overlapping and (CampaignTbl.campaignId neq except.value)
        }.orderBy(CampaignTbl.startsAt to SortOrder.ASC).limit(1).singleOrNull()?.toCampaign()

    override fun findLiveAt(now: Instant): Campaign? =
        CampaignTbl.selectAll().where {
            (CampaignTbl.isActive eq true) and (CampaignTbl.startsAt lessEq now.utc()) and (CampaignTbl.endsAt greater now.utc())
        }.orderBy(CampaignTbl.startsAt to SortOrder.ASC).limit(1).singleOrNull()?.toCampaign()

    override fun add(campaign: Campaign) {
        writing(campaign) {
            CampaignTbl.insert {
                it[campaignId] = campaign.id.value
                it[createdOn] = campaign.createdOn.utc()
                it.fill(campaign)
            }
        }
    }

    override fun save(campaign: Campaign) {
        writing(campaign) { CampaignTbl.update({ CampaignTbl.campaignId eq campaign.id.value }) { it.fill(campaign) } }
    }

    override fun delete(id: CampaignId): Boolean = CampaignTbl.deleteWhere { CampaignTbl.campaignId eq id.value } > 0

    /** The two walls of the table, as the exceptions the use cases answer with their own 409s. */
    private fun <T> writing(campaign: Campaign, write: () -> T): T = try {
        write()
    } catch (e: ExposedSQLException) {
        when (e.refusedConstraint()) {
            CampaignTbl.SLUG_KEY -> throw UniqueTakenException("campaign slug ${campaign.slug.value}", e)
            CampaignTbl.NO_OVERLAP -> throw CampaignOverlapException(e)
            else -> throw e
        }
    }

    private fun UpdateBuilder<*>.fill(campaign: Campaign) {
        this[CampaignTbl.slug] = campaign.slug.value
        this[CampaignTbl.title] = campaign.title
        this[CampaignTbl.subtitle] = campaign.subtitle
        this[CampaignTbl.icon] = campaign.icon?.value
        this[CampaignTbl.startsAt] = campaign.window.startsAt.utc()
        this[CampaignTbl.endsAt] = campaign.window.endsAt.utc()
        this[CampaignTbl.productIds] = campaign.productIds.map { it.value }
        this[CampaignTbl.isActive] = campaign.isActive
        this[CampaignTbl.updatedOn] = campaign.updatedOn.utc()
    }

    private fun ResultRow.toCampaign(): Campaign = Campaign.constructFromPersisted(
        id = CampaignId.constructFromPersisted(this[CampaignTbl.campaignId]),
        slug = Slug.constructFromPersisted(this[CampaignTbl.slug]),
        title = this[CampaignTbl.title],
        subtitle = this[CampaignTbl.subtitle],
        icon = this[CampaignTbl.icon]?.let { IconName.constructFromPersisted(it) },
        window = CampaignWindow(this[CampaignTbl.startsAt].toInstant(), this[CampaignTbl.endsAt].toInstant()),
        productIds = this[CampaignTbl.productIds].map { ProductId.constructFromPersisted(it) },
        isActive = this[CampaignTbl.isActive],
        createdOn = this[CampaignTbl.createdOn].toInstant(),
        updatedOn = this[CampaignTbl.updatedOn].toInstant(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        const val LOCK_KEY = "campaigns"
    }
}
