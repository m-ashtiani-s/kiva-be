package com.kiva.domain.aggregate.campaign.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.under
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignWindow
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import java.time.Instant

/**
 * A campaign as the panel's form sends it (`POST`/`PUT /admin/campaigns*`; roadmap section 16.2, decision 2), checked on
 * its own shape: every problem at once, each under its field. `PUT` replaces the whole campaign, so an optional field
 * left out is null (or its default) here too.
 *
 * Whether the products are there, the slug is free and no other active campaign overlaps is the use case's and
 * CampaignDomainService's, in the transaction.
 *
 * @property productIds the campaign's own choice, in the form's order; empty: every discounted product in the shop.
 */
@ConsistentCopyVisibility
data class CampaignDraft private constructor(
    val slug: Slug,
    val title: String,
    val subtitle: String?,
    val icon: IconName?,
    val window: CampaignWindow,
    val productIds: List<ProductId>,
    val isActive: Boolean,
) {
    companion object {
        const val MAX_SLUG = 60
        const val MAX_TITLE = 80
        const val MAX_SUBTITLE = 120
        const val MAX_PRODUCTS = 200

        /** [startsAt] and [endsAt] come already read (DATE_TIME_INVALID is the caller's); both are required. */
        fun makeNew(
            slug: String?,
            title: String?,
            subtitle: String?,
            icon: String?,
            startsAt: Result<Instant?, ResultFailure>,
            endsAt: Result<Instant?, ResultFailure>,
            productIds: List<Long>?,
            isActive: Boolean?,
        ): Result<CampaignDraft, InputProblems> {
            val problems = Problems()
            val address = problems.take(slugOf(slug).forField("slug"))
            val name = problems.take(titleOf(title).forField("title"))
            val sub = problems.take(optionalText(subtitle, MAX_SUBTITLE).forField("subtitle"))
            val sign = problems.take(iconOf(icon).forField("icon"))
            val from = problems.take(startsAt.bind { it.requiredMoment() }.forField("startsAt"))
            val until = problems.take(endsAt.bind { it.requiredMoment() }.forField("endsAt"))
            val products = problems.take(idsOf(productIds))
            val start = startsAt.getOrNull()
            val end = endsAt.getOrNull()
            if (start != null && end != null && !end.isAfter(start)) problems.add(FieldFailure("endsAt", Problem.EndsBeforeStart))
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(
                CampaignDraft(address.value, name.value, sub.value, sign.value, CampaignWindow(from.value, until.value), products.value, isActive ?: true),
            )
        }

        private fun Instant?.requiredMoment(): Result<Instant, ResultFailure> = if (this == null) Result.failure(FieldRequired) else Result.success(this)

        private fun slugOf(raw: String?): Result<Slug, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            if (text.length > MAX_SLUG) return Result.failure(TextTooLong(MAX_SLUG))
            return Slug.makeNew(text)
        }

        private fun titleOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return if (text.length > MAX_TITLE) Result.failure(TextTooLong(MAX_TITLE)) else Result.success(text)
        }

        private fun optionalText(raw: String?, max: Int): Result<String?, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.success(null)
            return if (text.length > max) Result.failure(TextTooLong(max)) else Result.success(text)
        }

        private fun iconOf(raw: String?): Result<IconName?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return IconName.makeNew(text)
        }

        /** Each id well-formed and once, under its index; at most [MAX_PRODUCTS]. Left out is none. */
        private fun idsOf(raw: List<Long>?): Result<List<ProductId>, ResultFailure> {
            val ids = raw.orEmpty()
            if (ids.size > MAX_PRODUCTS) return Result.failure(TooManyItems(MAX_PRODUCTS).under("productIds"))
            val problems = ids.mapIndexedNotNull { index, id ->
                when {
                    ids.indexOf(id) != index -> ListItemDuplicate.under("$index")
                    else -> ProductId.makeNew(id).failureOrNull()?.under("$index")
                }
            }
            if (problems.isNotEmpty()) return Result.failure(problems.under("productIds"))
            return Result.success(ids.map { ProductId.constructFromPersisted(it) })
        }
    }

    // No field here: makeNew puts it on `endsAt`.
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object EndsBeforeStart : Problem("DATE_RANGE_INVALID")
    }
}
