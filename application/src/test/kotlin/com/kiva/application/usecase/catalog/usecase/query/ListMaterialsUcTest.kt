package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.MaterialDto
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.MaterialFactory
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ListMaterialsUcTest {

    private val transactionManager = MockTransactionManager()
    private val materials = mockk<MaterialRepository>()
    private val useCase = ListMaterialsUc(transactionManager, materials)

    @Test
    fun `returns every material in the repository's order, in one transaction`() = runTest {
        every { materials.findAll() } returns listOf(
            MaterialFactory.generate(id = 1, slug = "genuine-cow-leather", careInstructions = listOf("با دستمال نرم تمیز شود")),
            MaterialFactory.generate(id = 11, slug = "satin", name = "ساتن", family = MaterialFamily.SATIN),
        )

        val result = useCase.execute(ListMaterialsUc.Qry)

        assertEquals(
            Result.success(
                listOf(
                    MaterialDto(
                        1, "genuine-cow-leather", "چرم طبیعی گاوی", MaterialFamily.GENUINE_LEATHER, null,
                        careInstructions = listOf("با دستمال نرم تمیز شود"), productCount = 0,
                    ),
                    MaterialDto(11, "satin", "ساتن", MaterialFamily.SATIN, null, careInstructions = emptyList(), productCount = 0),
                ),
            ),
            result,
        )
        assertEquals(1, transactionManager.transactionsOpened)
    }
}
