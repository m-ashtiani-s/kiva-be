package com.kiva.application.usecase.geo.usecase.query

import com.kiva.application.dto.CityDto
import com.kiva.application.dto.ProvinceDto
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.geo.entity.City
import com.kiva.domain.aggregate.geo.entity.Province
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ListProvincesUcTest {

    private val transactionManager = MockTransactionManager()
    private val geo = mockk<GeoRepository>()
    private val useCase = ListProvincesUc(transactionManager, geo)

    private fun province(id: Int, name: String, sortOrder: Int) =
        Province.constructFromPersisted(ProvinceId.constructFromPersisted(id), name, sortOrder)

    private fun city(id: Int, provinceId: Int, name: String, sortOrder: Int) =
        City.constructFromPersisted(CityId.constructFromPersisted(id), ProvinceId.constructFromPersisted(provinceId), name, sortOrder)

    private val provinces = listOf(province(4, "اصفهان", 4), province(8, "تهران", 8), province(31, "یزد", 31))
    private val cities = listOf(city(23, 4, "آران و بیدگل", 1), city(13, 8, "آبسرد", 1), city(394, 8, "تهران", 15))

    @Test
    fun `by default every province comes with its own cities, in the repository's order, and one with none gets an empty list`() = runTest {
        every { geo.findAllProvinces() } returns provinces
        every { geo.findAllCities() } returns cities

        val result = useCase.execute(ListProvincesUc.Qry())

        assertEquals(
            Result.success(
                listOf(
                    ProvinceDto(4, "اصفهان", listOf(CityDto(23, "آران و بیدگل", 4))),
                    ProvinceDto(8, "تهران", listOf(CityDto(13, "آبسرد", 8), CityDto(394, "تهران", 8))),
                    ProvinceDto(31, "یزد", emptyList()),
                ),
            ),
            result,
        )
        assertEquals(1, transactionManager.transactionsOpened)
    }

    @ParameterizedTest
    @ValueSource(strings = ["true", "TRUE", " True ", ""])
    fun `true in any case, or an empty value, includes the cities`(raw: String) = runTest {
        every { geo.findAllProvinces() } returns provinces
        every { geo.findAllCities() } returns cities

        val provincesOut = useCase.execute(ListProvincesUc.Qry(raw)).getOrNull()!!

        assertEquals(3, provincesOut.sumOf { it.cities!!.size })
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "False"])
    fun `false gives the provinces alone, with null cities, and never reads the cities`(raw: String) = runTest {
        every { geo.findAllProvinces() } returns provinces

        val result = useCase.execute(ListProvincesUc.Qry(raw))

        assertEquals(
            Result.success(listOf(ProvinceDto(4, "اصفهان", null), ProvinceDto(8, "تهران", null), ProvinceDto(31, "یزد", null))),
            result,
        )
        verify(exactly = 1) { geo.findAllProvinces() }
        confirmVerified(geo)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "1", "yes", "tru"])
    fun `anything but true or false is BOOLEAN_INVALID on includeCities, without touching the database`(raw: String) = runTest {
        val failure = useCase.execute(ListProvincesUc.Qry(raw)).failureOrNull()

        assertIs<ListProvincesUc.Failure.InvalidInput>(failure)
        assertEquals(listOf("includeCities" to "BOOLEAN_INVALID"), failure.causes.map { it.field to it.code })
        assertEquals(0, transactionManager.transactionsOpened)
        confirmVerified(geo) // nothing verified, so this fails if the repository was called at all
    }
}
