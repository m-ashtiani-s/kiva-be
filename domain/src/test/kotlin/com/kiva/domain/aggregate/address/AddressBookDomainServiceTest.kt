package com.kiva.domain.aggregate.address

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressIdGenerator
import com.kiva.domain.aggregate.geo.entity.City
import com.kiva.domain.aggregate.geo.entity.Province
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class AddressBookDomainServiceTest {

    private val now = Instant.parse("2026-10-11T08:00:00Z")

    /** Tehran (8) with its city 394, and Isfahan (4) with Kashan (1080). */
    private val geo = object : GeoRepository {
        private val provinces = listOf(province(8, "تهران"), province(4, "اصفهان"))
        private val cities = listOf(city(394, 8, "تهران"), city(1080, 4, "کاشان"))
        override fun findAllProvinces() = provinces
        override fun findAllCities() = cities
        override fun findProvince(id: ProvinceId) = provinces.firstOrNull { it.id == id }
        override fun findCitiesOf(provinceId: ProvinceId) = cities.filter { it.provinceId == provinceId }
        override fun findCity(id: CityId) = cities.firstOrNull { it.id == id }
        override fun findCities(ids: Set<CityId>) = cities.filter { it.id in ids }
    }

    private val ids = object : AddressIdGenerator {
        var next = 100L
        override fun nextAddressId() = AddressId.constructFromPersisted(next++)
    }

    private val service = AddressBookDomainService(geo, ids)
    private val book = AddressBook.empty(UserId.constructFromPersisted(7))

    private fun province(id: Int, name: String) = Province.constructFromPersisted(ProvinceId.constructFromPersisted(id), name, id)
    private fun city(id: Int, province: Int, name: String) =
        City.constructFromPersisted(CityId.constructFromPersisted(id), ProvinceId.constructFromPersisted(province), name, 1)

    @Test
    fun `a city of its own province is added, with the next id`() {
        val address = service.addAddress(book, addressInput(provinceId = 4, cityId = 1080), setAsDefault = false, now).getOrNull()!!

        assertEquals(100L, address.id.value)
        assertEquals(listOf(100L), book.addresses.map { it.id.value })
    }

    @Test
    fun `a province that doesn't exist is PROVINCE_NOT_FOUND on provinceId, and nothing is added`() {
        val result = service.addAddress(book, addressInput(provinceId = 99), setAsDefault = false, now)

        assertEquals(Result.failure(AddressBookDomainService.Failure.ProvinceNotFound), result)
        assertEquals("provinceId", AddressBookDomainService.Failure.ProvinceNotFound.field)
        assertEquals(emptyList(), book.addresses)
    }

    @Test
    fun `a city that doesn't exist is CITY_NOT_FOUND on cityId`() {
        assertEquals(Result.failure(AddressBookDomainService.Failure.CityNotFound), service.addAddress(book, addressInput(cityId = 9999), false, now))
    }

    @Test
    fun `the city of Tehran with the province of Isfahan is CITY_NOT_IN_PROVINCE, a 422`() {
        val result = service.addAddress(book, addressInput(provinceId = 4, cityId = 394), setAsDefault = false, now)

        assertEquals(Result.failure(AddressBookDomainService.Failure.CityNotInProvince), result)
        assertEquals("CITY_NOT_IN_PROVINCE", AddressBookDomainService.Failure.CityNotInProvince.code)
    }

    @Test
    fun `a full book passes the book's own failure through`() {
        repeat(AddressBook.MAX_ADDRESSES) { service.addAddress(book, addressInput(), false, now) }

        val result = service.addAddress(book, addressInput(), false, now)

        assertEquals(Result.failure(AddressBookDomainService.Failure.Book(AddressBook.Failure.LimitReached)), result)
    }

    @Test
    fun `editing an address that isn't in the book is ADDRESS_NOT_FOUND, before the place is even looked at`() {
        val result = service.updateAddress(book, AddressId.constructFromPersisted(5), addressInput(provinceId = 99), false, now)

        assertEquals(Result.failure(AddressBookDomainService.Failure.Book(AddressBook.Failure.AddressNotFound)), result)
    }

    @Test
    fun `editing to a city of another province is CITY_NOT_IN_PROVINCE, and the address stays as it was`() {
        val address = service.addAddress(book, addressInput(), false, now).getOrNull()!!

        val result = service.updateAddress(book, address.id, addressInput(provinceId = 4, cityId = 394), false, now)

        assertEquals(Result.failure(AddressBookDomainService.Failure.CityNotInProvince), result)
        assertEquals(8, book.find(address.id)!!.provinceId.value)
    }
}
