package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.media.repository.MediaRepository

class ListCategoriesUcTest {

    private val transactionManager = MockTransactionManager()
    private val categories = mockk<CategoryRepository>()
    private val productQm = mockk<ProductQmRepository>()
    private val media = mockk<MediaRepository>()
    private val useCase = ListCategoriesUc(transactionManager, categories, productQm, media)

    @Test
    fun `returns every category in the repository's order, hidden ones included, with their products in the shop, in one transaction`() = runTest {
        every { media.findAll(emptyList()) } returns emptyList()
        every { productQm.countActiveByCategory() } returns mapOf(Slug.constructFromPersisted("shoulder") to 2)
        every { categories.findAll() } returns listOf(
            CategoryFactory.generate(id = 1, slug = "shoulder", name = "کیف دوشی"),
            CategoryFactory.generate(
                id = 3, slug = "cross", name = "کراس‌بادی", description = "برای دست‌های آزاد",
                iconBagType = BagType.CROSSBODY, iconColorKey = "red", sortOrder = 3, showInMenu = false,
            ),
        )

        val result = useCase.execute(ListCategoriesUc.Qry)

        assertEquals(
            Result.success(
                listOf(
                    CategoryDto(1, "shoulder", "کیف دوشی", null, BagType.HOBO, "lilac", null, null, 1, showInMenu = true, SeoMeta.NONE, productCount = 2),
                    CategoryDto(
                        3, "cross", "کراس‌بادی", "برای دست‌های آزاد", BagType.CROSSBODY, "red", null, null, 3,
                        showInMenu = false, SeoMeta.NONE, productCount = 0,
                    ),
                ),
            ),
            result,
        )
        assertEquals(1, transactionManager.transactionsOpened)
    }
}
