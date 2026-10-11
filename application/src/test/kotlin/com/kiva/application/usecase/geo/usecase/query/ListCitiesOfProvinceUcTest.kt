package com.kiva.application.usecase.geo.usecase.query

import com.kiva.application.dto.CityDto
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class ListCitiesOfProvinceUcTest {

    private val transactionManager = MockTransactionManager()
    private val geo = mockk<GeoRepository>()
    private val useCase = ListCitiesOfProvinceUc(transactionManager, geo)

    // Never any() for a value-class parameter such as ProvinceId: MockK builds a stand-in through the constructor
    // with a random value, which init { require } refuses about half the time. Use real values.
    private val tehranId = ProvinceId.constructFromPersisted(8)
    private val tehran = Province.constructFromPersisted(tehranId, "تهران", 8)

    private fun city(id: Int, name: String, sortOrder: Int) = City.constructFromPersisted(CityId.constructFromPersisted(id), tehranId, name, sortOrder)

    @Test
    fun `an existing province returns its cities in the repository's order`() = runTest {
        every { geo.findProvince(tehranId) } returns tehran
        every { geo.findCitiesOf(tehranId) } returns listOf(city(13, "آبسرد", 1), city(394, "تهران", 15))

        val result = useCase.execute(ListCitiesOfProvinceUc.Qry("8"))

        assertEquals(Result.success(listOf(CityDto(13, "آبسرد", 8), CityDto(394, "تهران", 8))), result)
        assertEquals(1, transactionManager.transactionsOpened)
    }

    @Test
    fun `Persian digits and spaces around the id are accepted`() = runTest {
        every { geo.findProvince(tehranId) } returns tehran
        every { geo.findCitiesOf(tehranId) } returns listOf(city(394, "تهران", 15))

        val result = useCase.execute(ListCitiesOfProvinceUc.Qry(" ۸ "))

        assertEquals(Result.success(listOf(CityDto(394, "تهران", 8))), result)
    }

    @Test
    fun `a province that doesn't exist is ProvinceNotFound`() = runTest {
        every { geo.findProvince(ProvinceId.constructFromPersisted(99)) } returns null

        val result = useCase.execute(ListCitiesOfProvinceUc.Qry("99"))

        assertEquals(Result.failure(ListCitiesOfProvinceUc.Failure.ProvinceNotFound), result)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "0", "-8", "8.5", "", "99999999999"])
    fun `a segment that is not a positive number is ProvinceNotFound without touching the database`(raw: String) = runTest {
        val result = useCase.execute(ListCitiesOfProvinceUc.Qry(raw))

        assertEquals(Result.failure(ListCitiesOfProvinceUc.Failure.ProvinceNotFound), result)
        assertEquals(0, transactionManager.transactionsOpened)
        confirmVerified(geo) // nothing verified, so this fails if the repository was called at all
    }

    @Test
    fun `ProvinceNotFound uses the contract's general NOT_FOUND code`() {
        assertEquals("NOT_FOUND", ListCitiesOfProvinceUc.Failure.ProvinceNotFound.code)
    }
}
