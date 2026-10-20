package com.kiva.domain.aggregate.discount

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.DiscountTerms
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscountTermsTest {

    private val now = Instant.parse("2026-10-20T08:00:00Z")

    private data object BadMoment : ResultFailure("DATE_TIME_INVALID", FailureKind.INVALID)

    private fun terms(
        code: String? = "PAEEZ15",
        type: String? = "PERCENT",
        value: Long? = 15,
        label: String? = "۱۵٪ تخفیف پاییزه",
        minSubtotal: Long? = null,
        maxDiscount: Long? = null,
        startsAt: Result<Instant?, ResultFailure> = Result.success(null),
        endsAt: Result<Instant?, ResultFailure> = Result.success(null),
        usageLimit: Int? = null,
        perUserLimit: Int? = null,
        firstOrderOnly: Boolean? = null,
        requiresLogin: Boolean? = null,
        categoryIds: List<Long>? = null,
        productIds: List<Long>? = null,
        isActive: Boolean? = null,
        legacyAllowed: Boolean = false,
    ) = DiscountTerms.makeNew(
        code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly, requiresLogin, null,
        categoryIds, productIds, isActive, legacyAllowed,
    )

    private fun pathsOf(failure: ResultFailure?, parent: String? = null): List<String> {
        if (failure == null) return emptyList()
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { pathsOf(it, path) }
        return listOf(listOfNotNull(path, failure.code).joinToString(" "))
    }

    @Test
    fun `the code is normalised like a shopper's, and the defaults of the contract fill what was left out`() {
        val made = terms(code = " paeez۱۵ ").getOrNull()!!

        assertEquals("PAEEZ15", made.code.value)
        assertEquals(DiscountType.PERCENT, made.type)
        assertTrue(made.isActive)
        assertEquals(false, made.requiresLogin)
        assertNull(made.perUserLimit)
    }

    @Test
    fun `a first-order code or one with a per-customer limit is stored as needing a login`() {
        assertEquals(true, terms(firstOrderOnly = true, requiresLogin = false).getOrNull()?.requiresLogin)
        assertEquals(true, terms(perUserLimit = 1).getOrNull()?.requiresLogin)
        assertEquals(true, terms(requiresLogin = true).getOrNull()?.requiresLogin)
    }

    @Test
    fun `a percent of 1 to 100, a fixed amount of 1,000 to 100,000,000, else DISCOUNT_VALUE_INVALID`() {
        assertEquals(listOf("value DISCOUNT_VALUE_INVALID"), pathsOf(terms(value = 120).failureOrNull()))
        assertEquals(listOf("value DISCOUNT_VALUE_INVALID"), pathsOf(terms(value = 0).failureOrNull()))
        assertEquals(listOf("value DISCOUNT_VALUE_INVALID"), pathsOf(terms(type = "FIXED", value = 500).failureOrNull()))
        assertEquals(100, terms(value = 100).getOrNull()?.value)
        assertEquals(1_000, terms(type = "FIXED", value = 1_000).getOrNull()?.value)
    }

    @Test
    fun `a cap only for a percent - with a fixed amount MAX_DISCOUNT_NOT_ALLOWED, out of range NUMBER_OUT_OF_RANGE`() {
        assertEquals(listOf("maxDiscount MAX_DISCOUNT_NOT_ALLOWED"), pathsOf(terms(type = "FIXED", value = 150_000, maxDiscount = 50_000).failureOrNull()))
        assertEquals(listOf("maxDiscount NUMBER_OUT_OF_RANGE"), pathsOf(terms(maxDiscount = 999).failureOrNull()))
        assertEquals(500_000L, terms(maxDiscount = 500_000).getOrNull()?.maxDiscount?.toman)
    }

    @Test
    fun `a 2-letter code is DISCOUNT_CODE_TEXT_INVALID for a new one, allowed for an edit - blank is FIELD_REQUIRED`() {
        assertEquals(listOf("code DISCOUNT_CODE_TEXT_INVALID"), pathsOf(terms(code = "AB").failureOrNull()))
        assertEquals(listOf("code DISCOUNT_CODE_TEXT_INVALID"), pathsOf(terms(code = "پاییز").failureOrNull()))
        assertEquals(listOf("code FIELD_REQUIRED"), pathsOf(terms(code = "  ").failureOrNull()))
        assertTrue(terms(code = "AB", legacyAllowed = true).getOrNull()!!.code.isLegacy)
    }

    @Test
    fun `every problem at once, each under its own field`() {
        val failure = terms(
            code = null, type = "HALF", value = null, label = "", minSubtotal = -1, startsAt = Result.failure(BadMoment), usageLimit = 0, perUserLimit = 1_000_001,
            categoryIds = listOf(3, 3, 0), productIds = List(201) { it + 1L },
        ).failureOrNull()

        assertEquals(
            listOf(
                "code FIELD_REQUIRED", "type DISCOUNT_TYPE_INVALID", "value FIELD_REQUIRED", "label FIELD_REQUIRED", "minSubtotal NUMBER_OUT_OF_RANGE",
                "startsAt DATE_TIME_INVALID", "usageLimit NUMBER_OUT_OF_RANGE", "perUserLimit NUMBER_OUT_OF_RANGE", "categoryIds.1 LIST_ITEM_DUPLICATE",
                "categoryIds.2 CATEGORY_ID_INVALID", "productIds TOO_MANY_ITEMS",
            ),
            pathsOf(failure),
        )
    }

    @Test
    fun `it ends after it starts, or DATE_RANGE_INVALID on endsAt`() {
        assertEquals(
            listOf("endsAt DATE_RANGE_INVALID"),
            pathsOf(terms(startsAt = Result.success(now), endsAt = Result.success(now)).failureOrNull()),
        )
        assertEquals(now.plusSeconds(1), terms(startsAt = Result.success(now), endsAt = Result.success(now.plusSeconds(1))).getOrNull()?.endsAt)
    }

    @Test
    fun `a code is made with its terms, a change of nothing is none, a change keeps when it was made`() {
        val made = DiscountCode.create(DiscountCodeId.constructFromPersisted(9), terms(categoryIds = listOf(4, 2)).getOrNull()!!, now)

        assertEquals(now, made.createdOn)
        assertEquals(0, made.version)
        assertNull(made.change(terms(categoryIds = listOf(2, 4)).getOrNull()!!, now.plusSeconds(60)))

        val changed = made.change(terms(value = 20, categoryIds = listOf(2, 4)).getOrNull()!!, now.plusSeconds(60))!!
        assertEquals(20, changed.value)
        assertEquals(now, changed.createdOn)
        assertEquals(now.plusSeconds(60), changed.updatedOn)
    }

    @Test
    fun `switching off is once - an inactive code has nothing to switch off`() {
        val made = DiscountCode.create(DiscountCodeId.constructFromPersisted(9), terms().getOrNull()!!, now)

        val off = made.deactivate(now.plusSeconds(1))!!
        assertEquals(false, off.isActive)
        assertNull(off.deactivate(now.plusSeconds(2)))
    }
}
