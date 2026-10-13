package com.kiva.infrastructure.seed

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountCodeTbl
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The prototype's three discount codes for a development database (roadmap section 14): KIVA10 (10% of the whole cart),
 * WELCOME (150,000 toman off a first order, with a login, once per customer) and PAEEZ15 (15% from a subtotal of
 * 2,000,000). The panel's discount codes aren't built, so codes are rows, made here the way a server's are made by SQL.
 *
 * Runs on startup only when `kiva.seed.enabled` is on, after the catalog. A code already there is left as it is, so
 * running it again adds nothing.
 */
class DevDiscountCodeSeeder(private val transactionManager: TransactionManager, private val clock: Clock) {

    private val logger = getLogger()

    /** How many codes this run made. */
    suspend fun seed(): Int {
        val now = clock.instant()
        val made = transactionManager.inTransaction { Result.success(CODES.count { DiscountCodeRows.insertIfAbsent(it, now) }) }.unwrapOr { it }
        logger.info("DevDiscountCodeSeeder: $made codes made, ${CODES.size - made} already there")
        return made
    }

    companion object {
        val KIVA10 = DiscountCodeRow("KIVA10", DiscountType.PERCENT, 10, "۱۰٪ تخفیف روی کل سبد")

        val WELCOME = DiscountCodeRow(
            "WELCOME", DiscountType.FIXED, 150_000, "۱۵۰٬۰۰۰ تومان تخفیف اولین خرید", firstOrderOnly = true, requiresLogin = true, perUserLimit = 1,
        )

        val PAEEZ15 = DiscountCodeRow("PAEEZ15", DiscountType.PERCENT, 15, "۱۵٪ تخفیف پاییزه (خرید بالای ۲ میلیون)", minSubtotal = 2_000_000)

        val CODES = listOf(KIVA10, WELCOME, PAEEZ15)
    }
}

/** A discount code as a row of discountcode_tbl: the columns of the contract's DiscountCodeUpsertRequest. */
data class DiscountCodeRow(
    val code: String,
    val type: DiscountType,
    val value: Int,
    val label: String,
    val minSubtotal: Long? = null,
    val maxDiscount: Long? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val usageLimit: Int? = null,
    val perUserLimit: Int? = null,
    val firstOrderOnly: Boolean = false,
    val requiresLogin: Boolean = false,
    val excludeSaleItems: Boolean = false,
    val categoryIds: List<Long> = emptyList(),
    val productIds: List<Long> = emptyList(),
    val isActive: Boolean = true,
)

/** Writes codes as SQL would, inside the caller's transaction. */
object DiscountCodeRows {

    /** Adds [row], made [now], unless a code with its text is there; true when it was added. */
    fun insertIfAbsent(row: DiscountCodeRow, now: Instant): Boolean = DiscountCodeTbl.insertIgnore {
        it[code] = row.code
        it[type] = row.type
        it[value] = row.value
        it[label] = row.label
        it[minSubtotal] = row.minSubtotal
        it[maxDiscount] = row.maxDiscount
        it[startsAt] = row.startsAt?.atOffset(ZoneOffset.UTC)
        it[endsAt] = row.endsAt?.atOffset(ZoneOffset.UTC)
        it[usageLimit] = row.usageLimit
        it[perUserLimit] = row.perUserLimit
        it[firstOrderOnly] = row.firstOrderOnly
        it[requiresLogin] = row.requiresLogin
        it[excludeSaleItems] = row.excludeSaleItems
        it[categoryIds] = row.categoryIds
        it[productIds] = row.productIds
        it[isActive] = row.isActive
        it[createdOn] = now.atOffset(ZoneOffset.UTC)
    }.insertedCount == 1
}
