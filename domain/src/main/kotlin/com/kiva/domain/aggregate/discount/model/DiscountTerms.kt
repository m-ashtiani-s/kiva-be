package com.kiva.domain.aggregate.discount.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.NumberOutOfRange
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.forField
import com.kiva.common.railway.under
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * Everything the panel sets on a discount code (the contract's DiscountCodeUpsertRequest), each value checked: what a
 * DiscountCode is made with and changed to (roadmap phase 4, decisions 9, 10 and 22).
 *
 * [requiresLogin] is the stored one: asked for, or needed because a guest's uses and orders can't be counted (first
 * order only, a per-customer limit). Whether the categories and products exist is the use case's: it needs to read them.
 */
@ConsistentCopyVisibility
data class DiscountTerms private constructor(
    val code: DiscountCodeText,
    val type: DiscountType,
    val value: Int,
    val label: String,
    val minSubtotal: Money?,
    val maxDiscount: Money?,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val usageLimit: Int?,
    val perUserLimit: Int?,
    val firstOrderOnly: Boolean,
    val requiresLogin: Boolean,
    val excludeSaleItems: Boolean,
    val categoryIds: List<CategoryId>,
    val productIds: List<ProductId>,
    val isActive: Boolean,
) {
    init {
        require(requiresLogin || (!firstOrderOnly && perUserLimit == null)) { "Code ${code.value}: a guest can't be counted, so it needs a login" }
    }

    companion object {
        val PERCENT = 1..100
        val FIXED = 1_000L..100_000_000L
        val MAX_DISCOUNT = 1_000L..100_000_000L
        val MIN_SUBTOTAL = 0L..1_000_000_000L
        val LIMIT = 1..1_000_000
        const val MAX_LABEL_LENGTH = 200
        const val MAX_CATEGORIES = 50
        const val MAX_PRODUCTS = 200

        /**
         * The form, every problem at once under its field (`value`, `categoryIds.2`). [startsAt] and [endsAt] come
         * already read (DATE_TIME_INVALID is the caller's); [legacyAllowed] for an edit (DiscountCodeText.makeNewInPanel).
         */
        fun makeNew(
            code: String?,
            type: String?,
            value: Long?,
            label: String?,
            minSubtotal: Long?,
            maxDiscount: Long?,
            startsAt: Result<Instant?, ResultFailure>,
            endsAt: Result<Instant?, ResultFailure>,
            usageLimit: Int?,
            perUserLimit: Int?,
            firstOrderOnly: Boolean?,
            requiresLogin: Boolean?,
            excludeSaleItems: Boolean?,
            categoryIds: List<Long>?,
            productIds: List<Long>?,
            isActive: Boolean?,
            legacyAllowed: Boolean = false,
        ): Result<DiscountTerms, InputProblems> {
            val problems = Problems()
            val text = problems.take(DiscountCodeText.makeNewInPanel(code, legacyAllowed))
            val knownType = DiscountType.entries.firstOrNull { it.name == type }
            val kind = problems.take(knownType?.let { Result.success(it) } ?: Result.failure(typeProblemOf(type)))
            val amount = problems.take(valueOf(value, knownType))
            val name = problems.take(labelOf(label))
            val minimum = problems.take(optionalIn(minSubtotal, MIN_SUBTOTAL, "minSubtotal"))
            val cap = problems.take(maxDiscountOf(maxDiscount, knownType))
            val from = problems.take(startsAt.forField("startsAt"))
            val until = problems.take(endsAt.forField("endsAt"))
            val uses = problems.take(optionalIn(usageLimit?.toLong(), LIMIT.first.toLong()..LIMIT.last.toLong(), "usageLimit"))
            val perCustomer = problems.take(optionalIn(perUserLimit?.toLong(), LIMIT.first.toLong()..LIMIT.last.toLong(), "perUserLimit"))
            val categories = problems.take(idsOf(categoryIds, "categoryIds", MAX_CATEGORIES) { CategoryId.makeNew(it) })
            val products = problems.take(idsOf(productIds, "productIds", MAX_PRODUCTS) { ProductId.makeNew(it) })
            val start = startsAt.getOrNull()
            val end = endsAt.getOrNull()
            if (start != null && end != null && !end.isAfter(start)) problems.add(Problem.EndsBeforeStart)
            if (problems.any()) return Result.failure(problems.all())

            val first = firstOrderOnly ?: false
            val perUser = perCustomer.value?.toInt()
            return Result.success(
                DiscountTerms(
                    code = text.value,
                    type = kind.value,
                    value = amount.value,
                    label = name.value,
                    minSubtotal = minimum.value?.let(Money::constructFromPersisted),
                    maxDiscount = cap.value?.let(Money::constructFromPersisted),
                    startsAt = from.value,
                    endsAt = until.value,
                    usageLimit = uses.value?.toInt(),
                    perUserLimit = perUser,
                    firstOrderOnly = first,
                    requiresLogin = (requiresLogin ?: false) || first || perUser != null,
                    excludeSaleItems = excludeSaleItems ?: false,
                    categoryIds = categories.value,
                    productIds = products.value,
                    isActive = isActive ?: true,
                ),
            )
        }

        private fun typeProblemOf(type: String?): ResultFailure = if (type == null) FieldRequired.under("type") else Problem.TypeInvalid

        // The value's range is its type's; without a type there is nothing to check it against (the type is already wrong).
        private fun valueOf(value: Long?, type: DiscountType?): Result<Int, ResultFailure> = when {
            value == null -> Result.failure(FieldRequired.under("value"))
            type == null -> Result.success(0)
            type == DiscountType.PERCENT && value in PERCENT.first.toLong()..PERCENT.last.toLong() -> Result.success(value.toInt())
            type == DiscountType.FIXED && value in FIXED -> Result.success(value.toInt())
            else -> Result.failure(Problem.ValueInvalid)
        }

        private fun maxDiscountOf(raw: Long?, type: DiscountType?): Result<Long?, ResultFailure> = when {
            raw == null -> Result.success(null)
            type == DiscountType.FIXED -> Result.failure(Problem.MaxDiscountNotAllowed)
            raw !in MAX_DISCOUNT -> Result.failure(NumberOutOfRange(MAX_DISCOUNT.first, MAX_DISCOUNT.last).under("maxDiscount"))
            else -> Result.success(raw)
        }

        private fun labelOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters().orEmpty()
            return when {
                text.isEmpty() -> Result.failure(FieldRequired.under("label"))
                text.length > MAX_LABEL_LENGTH -> Result.failure(TextTooLong(MAX_LABEL_LENGTH).under("label"))
                else -> Result.success(text)
            }
        }

        private fun optionalIn(raw: Long?, range: LongRange, field: String): Result<Long?, ResultFailure> = when {
            raw == null -> Result.success(null)
            raw !in range -> Result.failure(NumberOutOfRange(range.first, range.last).under(field))
            else -> Result.success(raw)
        }

        /** Each id well-formed and once, under its index; at most [max] of them. Left out is none. */
        private fun <T> idsOf(raw: List<Long>?, field: String, max: Int, makeNew: (Long) -> Result<T, ResultFailure>): Result<List<T>, ResultFailure> {
            val ids = raw.orEmpty()
            if (ids.size > max) return Result.failure(TooManyItems(max).under(field))
            val problems = ids.mapIndexedNotNull { index, id ->
                when {
                    ids.indexOf(id) != index -> ListItemDuplicate.under("$index")
                    else -> makeNew(id).failureOrNull()?.under("$index")
                }
            }
            if (problems.isNotEmpty()) return Result.failure(problems.under(field))
            return Result.success(ids.map { (makeNew(it) as Result.Success).value })
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            code: DiscountCodeText,
            type: DiscountType,
            value: Int,
            label: String,
            minSubtotal: Money?,
            maxDiscount: Money?,
            startsAt: Instant?,
            endsAt: Instant?,
            usageLimit: Int?,
            perUserLimit: Int?,
            firstOrderOnly: Boolean,
            requiresLogin: Boolean,
            excludeSaleItems: Boolean,
            categoryIds: List<CategoryId>,
            productIds: List<ProductId>,
            isActive: Boolean,
        ): DiscountTerms = DiscountTerms(
            code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly, requiresLogin,
            excludeSaleItems, categoryIds, productIds, isActive,
        )
    }

    sealed class Problem(code: String, field: String) : ResultFailure(code, FailureKind.INVALID, field) {
        data object TypeInvalid : Problem("DISCOUNT_TYPE_INVALID", "type")

        data object ValueInvalid : Problem("DISCOUNT_VALUE_INVALID", "value")

        data object MaxDiscountNotAllowed : Problem("MAX_DISCOUNT_NOT_ALLOWED", "maxDiscount")

        data object EndsBeforeStart : Problem("DATE_RANGE_INVALID", "endsAt")
    }
}
