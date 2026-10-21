package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.DayTotals
import com.kiva.application.repository.PeriodTotals
import com.kiva.application.repository.PhotoOutcomes
import com.kiva.application.repository.ReportQmRepository
import com.kiva.application.repository.TopProductQm
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The report's sums, each one SQL statement over orderqm_tbl (V15, grown by V26, V29 and V30): the jsonb lines and the
 * Tehran day are plain SQL, which Exposed's DSL would only hide. The ranges' ends are bound as text and cast, so an
 * instant reaches Postgres exactly as written. Runs inside the use case's transaction; it never opens one itself.
 */
class ReportQmRepositoryImpl : ReportQmRepository {

    override fun periodTotals(from: Instant, until: Instant): PeriodTotals {
        val totals = query(
            """
            SELECT count(*), coalesce(sum($KEPT), 0), coalesce(sum($CODE_DISCOUNT), 0), count("reservationGroupId")
              FROM orderqm_tbl
             WHERE $PERIOD
            """,
            range(from, until),
        ) { rs ->
            rs.next()
            PeriodTotals(
                ordersCount = rs.getInt(1),
                revenue = Money.constructFromPersisted(rs.getLong(2)),
                discountTotal = Money.constructFromPersisted(rs.getLong(3)),
                inReservation = rs.getInt(4),
                byShippingMethod = emptyMap(),
                byMessenger = emptyMap(),
            )
        }
        if (totals.ordersCount == 0) return PeriodTotals.NONE
        return totals.copy(
            byShippingMethod = countsBy("\"shippingMethod\"", from, until).mapKeys { ShippingMethodCode.valueOf(it.key) },
            byMessenger = countsBy("\"messengerChannel\"", from, until).mapKeys { MessengerChannel.valueOf(it.key) },
        )
    }

    /** How many of the period's orders have each value of [column] (an enum's name); a value none has is left out. */
    private fun countsBy(column: String, from: Instant, until: Instant): Map<String, Int> =
        query("SELECT $column, count(*) FROM orderqm_tbl WHERE $PERIOD GROUP BY $column", range(from, until)) { rs ->
            generateSequence { if (rs.next()) rs.getString(1) to rs.getInt(2) else null }.toMap()
        }

    override fun dailyTotals(from: Instant, until: Instant, zone: ZoneId): Map<LocalDate, DayTotals> =
        query(
            """
            SELECT ("paidOn" AT TIME ZONE ?)::date AS day, count(*), sum($KEPT)
              FROM orderqm_tbl
             WHERE $PERIOD
             GROUP BY day
            """,
            listOf(TextColumnType() to zone.id) + range(from, until),
        ) { rs ->
            generateSequence {
                if (rs.next()) rs.getObject(1, LocalDate::class.java) to DayTotals(Money.constructFromPersisted(rs.getLong(3)), rs.getInt(2)) else null
            }.toMap()
        }

    override fun newCustomers(from: Instant, until: Instant): Int =
        query(
            """
            SELECT count(*)
              FROM (SELECT min("paidOn") AS first FROM orderqm_tbl WHERE status IN ($STANDING) GROUP BY "userId") firsts
             WHERE first >= CAST(? AS timestamptz) AND first < CAST(? AS timestamptz)
            """,
            range(from, until),
        ) { rs ->
            rs.next()
            rs.getInt(1)
        }

    override fun photoOutcomes(from: Instant, until: Instant): PhotoOutcomes =
        query(
            """
            SELECT count(*), count(*) FILTER (WHERE "changeRequestedEver")
              FROM orderqm_tbl
             WHERE "firstPhotosSentOn" >= CAST(? AS timestamptz) AND "firstPhotosSentOn" < CAST(? AS timestamptz)
            """,
            range(from, until),
        ) { rs ->
            rs.next()
            PhotoOutcomes(sent = rs.getInt(1), changeRequested = rs.getInt(2))
        }

    /** The ranking over the lines in SQL, joined to the product as it is today; then one query for their pictures. */
    override fun topProducts(from: Instant, until: Instant, limit: Int): List<TopProductQm> {
        val ranked = query(
            """
            SELECT p."productId", p.slug, p.sku, p.name, sold.units, sold.revenue
              FROM (SELECT (line->>'productId')::bigint AS "productId",
                           sum((line->>'quantity')::int) AS units,
                           sum((line->'priceTag'->>'price')::bigint * (line->>'quantity')::int) AS revenue
                      FROM orderqm_tbl CROSS JOIN LATERAL jsonb_array_elements(lines) AS line
                     WHERE $PERIOD
                     GROUP BY 1) sold
              JOIN productqm_tbl p ON p."productId" = sold."productId"
             ORDER BY sold.units DESC, sold."productId"
             LIMIT ?
            """,
            range(from, until) + (IntegerColumnType() to limit),
        ) { rs -> generateSequence { if (rs.next()) rs.toTopProduct() else null }.toList() }

        val photos = defaultPhotosOf(ranked.map { it.productId.value })
        return ranked.map { it.copy(image = photos[it.productId.value]) }
    }

    private fun ResultSet.toTopProduct() = TopProductQm(
        productId = ProductId.constructFromPersisted(getLong(1)),
        slug = Slug.constructFromPersisted(getString(2)),
        sku = Sku.constructFromPersisted(getString(3)),
        name = ProductName.constructFromPersisted(getString(4)),
        image = null,
        unitsSold = Math.toIntExact(getLong(5)),
        revenue = Money.constructFromPersisted(getLong(6)),
    )

    /** The first photo (not a video) of each product's default color, for those of [productIds] that have one. */
    private fun defaultPhotosOf(productIds: List<Long>): Map<Long, UploadedMediaDto> {
        if (productIds.isEmpty()) return emptyMap()
        return ProductVariantQmTbl.select(ProductVariantQmTbl.productId, ProductVariantQmTbl.media)
            .where { (ProductVariantQmTbl.productId inList productIds) and (ProductVariantQmTbl.isDefault eq true) }
            .mapNotNull { row -> row[ProductVariantQmTbl.media].firstOrNull { !it.isVideo }?.let { row[ProductVariantQmTbl.productId] to it.toDto().media } }
            .toMap()
    }

    private fun range(from: Instant, until: Instant): List<Pair<IColumnType<*>, Any?>> =
        listOf(TextColumnType() to from.toString(), TextColumnType() to until.toString())

    private fun <T : Any> query(sql: String, args: List<Pair<IColumnType<*>, Any?>>, read: (ResultSet) -> T): T =
        TransactionManager.current().exec(sql.trimIndent(), args) { read(it) } ?: error("The report's query returned nothing: $sql")

    private companion object {
        /** «سفارش دوره»: paid in the range and not cancelled; its two `?` are the range's ends. */
        const val PERIOD = """"paidOn" >= CAST(? AS timestamptz) AND "paidOn" < CAST(? AS timestamptz) AND status <> 'CANCELLED'"""

        /** What an order was paid (at checkout, and a balance after a change) less what went back. */
        const val KEPT = """payable + "balancePaid" - refunded"""

        /** The code's discount as the order shows it now: placed, less the share of the lines taken out. */
        const val CODE_DISCOUNT = """(totals->>'codeDiscount')::bigint - "codeDiscountRemoved""""

        /** The statuses of a purchase that stands (OrderStatus.countsAsPurchase), as SQL literals. */
        val STANDING = OrderStatus.entries.filter { it.countsAsPurchase }.joinToString { "'${it.name}'" }
    }
}
