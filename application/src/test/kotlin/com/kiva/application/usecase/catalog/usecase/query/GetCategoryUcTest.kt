package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class GetCategoryUcTest {

    private val transactionManager = MockTransactionManager()
    private val categories = mockk<CategoryRepository>()
    private val useCase = GetCategoryUc(transactionManager, categories)

    @Test
    fun `an existing slug returns its category`() = runTest {
        every { categories.findBySlug(Slug.constructFromPersisted("shoulder")) } returns CategoryFactory.generate()

        val result = useCase.execute(GetCategoryUc.Qry("shoulder"))

        assertEquals(
            Result.success(CategoryDto(1, "shoulder", "کیف دوشی", null, BagType.HOBO, "lilac", 1, showInMenu = true, productCount = 0)),
            result,
        )
        assertEquals(1, transactionManager.transactionsOpened)
    }

    // Never any() for a value-class parameter such as Slug: MockK builds a stand-in through the constructor
    // with a random value, which init { require } refuses about half the time. Use real values.

    @Test
    fun `an unknown slug is CategoryNotFound`() = runTest {
        every { categories.findBySlug(Slug.constructFromPersisted("nope")) } returns null

        val result = useCase.execute(GetCategoryUc.Qry("nope"))

        assertEquals(Result.failure(GetCategoryUc.Failure.CategoryNotFound), result)
    }

    @ParameterizedTest
    @ValueSource(strings = ["12", "Shoulder", "shoulder bags", ""])
    fun `a malformed slug is CategoryNotFound without touching the database`(raw: String) = runTest {
        val result = useCase.execute(GetCategoryUc.Qry(raw))

        assertEquals(Result.failure(GetCategoryUc.Failure.CategoryNotFound), result)
        assertEquals(0, transactionManager.transactionsOpened)
        confirmVerified(categories) // nothing verified, so this fails if the repository was called at all
    }

    @Test
    fun `CategoryNotFound uses the contract's general NOT_FOUND code`() {
        assertEquals("NOT_FOUND", GetCategoryUc.Failure.CategoryNotFound.code)
    }
}
