package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.ColorDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.ColorFactory
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ListColorsUcTest {

    private val transactionManager = MockTransactionManager()
    private val colors = mockk<ColorRepository>()
    private val productQm = mockk<ProductQmRepository>()
    private val useCase = ListColorsUc(transactionManager, colors, productQm)

    @Test
    fun `returns the palette in the repository's order, each with the products in the shop that offer it, in one transaction`() = runTest {
        every { productQm.countActiveByColor() } returns mapOf(ColorKey.constructFromPersisted("lilac") to 3)
        every { colors.findAll() } returns listOf(
            ColorFactory.generate(key = "black", name = "مشکی", hex = "#2B2930", isLight = false, sortOrder = 1),
            ColorFactory.generate(key = "lilac", name = "یاسی", hex = "#C8B6E2", isLight = true, sortOrder = 4),
        )

        val result = useCase.execute(ListColorsUc.Qry)

        assertEquals(
            Result.success(
                listOf(
                    ColorDto("black", "مشکی", "#2B2930", isLight = false, sortOrder = 1, productCount = 0),
                    ColorDto("lilac", "یاسی", "#C8B6E2", isLight = true, sortOrder = 4, productCount = 3),
                ),
            ),
            result,
        )
        assertEquals(1, transactionManager.transactionsOpened)
    }

    @Test
    fun `an empty palette is an empty list, not a failure`() = runTest {
        every { productQm.countActiveByColor() } returns emptyMap()
        every { colors.findAll() } returns emptyList()

        assertEquals(Result.success(emptyList()), useCase.execute(ListColorsUc.Qry))
    }
}
