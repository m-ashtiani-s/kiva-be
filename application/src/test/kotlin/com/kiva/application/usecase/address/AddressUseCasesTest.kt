package com.kiva.application.usecase.address

import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.address.repository.AddressIdGenerator
import com.kiva.domain.aggregate.geo.entity.City
import com.kiva.domain.aggregate.geo.entity.Province
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The six address book use cases over in-memory repositories: input checks, failure mapping and the list's order. */
class AddressUseCasesTest {

    private val transactionManager = MockTransactionManager()
    private val clock = Clock.fixed(Instant.parse("2026-10-11T08:00:00Z"), ZoneOffset.UTC)
    private val sara = UserId.constructFromPersisted(7)

    private val books = object : AddressBookRepository {
        val stored = mutableMapOf<UserId, List<Address>>()
        override fun find(userId: UserId) = AddressBook.constructFromPersisted(userId, stored[userId].orEmpty())
        override fun lockAndFind(userId: UserId) = find(userId)
        override fun save(book: AddressBook) {
            stored[book.userId] = book.addresses
        }
    }

    private val geo = object : GeoRepository {
        private val provinces = listOf(Province.constructFromPersisted(ProvinceId.constructFromPersisted(8), "تهران", 8))
        private val cities = listOf(City.constructFromPersisted(CityId.constructFromPersisted(394), ProvinceId.constructFromPersisted(8), "تهران", 15))
        override fun findAllProvinces() = provinces
        override fun findAllCities() = cities
        override fun findProvince(id: ProvinceId) = provinces.firstOrNull { it.id == id }
        override fun findCitiesOf(provinceId: ProvinceId) = cities.filter { it.provinceId == provinceId }
        override fun findCity(id: CityId) = cities.firstOrNull { it.id == id }
        override fun findCities(ids: Set<CityId>) = cities.filter { it.id in ids }
    }

    private val ids = object : AddressIdGenerator {
        var next = 1L
        override fun nextAddressId() = AddressId.constructFromPersisted(next++)
    }

    private val service = AddressBookDomainService(geo, ids)
    private val add = AddAddressUc(transactionManager, service, books, clock)
    private val update = UpdateAddressUc(transactionManager, service, books, clock)
    private val delete = DeleteAddressUc(transactionManager, books, clock)
    private val setDefault = SetDefaultAddressUc(transactionManager, books, clock)
    private val list = ListMyAddressesUc(transactionManager, books, geo)
    private val get = GetMyAddressUc(transactionManager, books, geo)

    private fun fields(provinceId: Int? = 8, postalCode: String? = "1965843117", recipientPhone: String? = "09123456789") = AddressFields(
        title = null, provinceId = provinceId, cityId = 394, addressLine = "خیابان ولیعصر، کوچه نسترن، پلاک ۱۲", plaque = "12", unit = null,
        postalCode = postalCode, recipientName = "سارا محمدی", recipientPhone = recipientPhone, isSelfRecipient = true, lat = null, lng = null,
    )

    @Test
    fun `an added address reads back with the names of its province and city`() = runTest {
        val id = add.execute(AddAddressUc.Cmd(sara, fields(), setAsDefault = null)).getOrNull()!!

        val address = get.execute(GetMyAddressUc.Qry(sara, id.value.toString())).getOrNull()!!

        assertEquals(listOf("تهران", "تهران"), listOf(address.provinceName, address.cityName))
        assertEquals(true, address.isDefault)
        assertEquals(clock.instant(), address.createdOn)
    }

    @Test
    fun `wrong fields are one InvalidInput with all of them, and no transaction is opened`() = runTest {
        val failure = add.execute(AddAddressUc.Cmd(sara, fields(postalCode = "123", recipientPhone = "0912"), setAsDefault = null)).failureOrNull()

        assertIs<AddAddressUc.Failure.InvalidInput>(failure)
        assertEquals(listOf("postalCode", "recipientPhone"), failure.problems.map { it.field })
        assertEquals(0, transactionManager.transactionsOpened)
    }

    @Test
    fun `a province that doesn't exist is ProvinceNotFound on provinceId, for adding and editing`() = runTest {
        assertEquals(Result.failure(AddAddressUc.Failure.ProvinceNotFound), add.execute(AddAddressUc.Cmd(sara, fields(provinceId = 99), null)))

        val id = add.execute(AddAddressUc.Cmd(sara, fields(), null)).getOrNull()!!
        assertEquals(
            Result.failure(UpdateAddressUc.Failure.ProvinceNotFound),
            update.execute(UpdateAddressUc.Cmd(sara, id.value.toString(), fields(provinceId = 99), null)),
        )
    }

    @Test
    fun `the list starts with the default, then the rest oldest first`() = runTest {
        val first = add.execute(AddAddressUc.Cmd(sara, fields(), null)).getOrNull()!!
        val second = add.execute(AddAddressUc.Cmd(sara, fields(), null)).getOrNull()!!
        val third = add.execute(AddAddressUc.Cmd(sara, fields(), null)).getOrNull()!!

        setDefault.execute(SetDefaultAddressUc.Cmd(sara, third.value.toString()))

        assertEquals(listOf(third, first, second).map { it.value }, list.execute(ListMyAddressesUc.Qry(sara)).getOrNull()!!.map { it.id })
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "0", "-1", ""])
    fun `a segment that is not a positive number is AddressNotFound for every use case, before any transaction`(raw: String) = runTest {
        assertEquals(Result.failure(GetMyAddressUc.Failure.AddressNotFound), get.execute(GetMyAddressUc.Qry(sara, raw)))
        assertEquals(Result.failure(UpdateAddressUc.Failure.AddressNotFound), update.execute(UpdateAddressUc.Cmd(sara, raw, fields(), null)))
        assertEquals(Result.failure(DeleteAddressUc.Failure.AddressNotFound), delete.execute(DeleteAddressUc.Cmd(sara, raw)))
        assertEquals(Result.failure(SetDefaultAddressUc.Failure.AddressNotFound), setDefault.execute(SetDefaultAddressUc.Cmd(sara, raw)))
        assertEquals(0, transactionManager.transactionsOpened)
    }

    @Test
    fun `another customer's address is AddressNotFound, and it stays as it was`() = runTest {
        val sarasAddress = add.execute(AddAddressUc.Cmd(sara, fields(), null)).getOrNull()!!.value.toString()
        val mina = UserId.constructFromPersisted(8)

        assertEquals(Result.failure(GetMyAddressUc.Failure.AddressNotFound), get.execute(GetMyAddressUc.Qry(mina, sarasAddress)))
        assertEquals(Result.failure(UpdateAddressUc.Failure.AddressNotFound), update.execute(UpdateAddressUc.Cmd(mina, sarasAddress, fields(), true)))
        assertEquals(Result.failure(DeleteAddressUc.Failure.AddressNotFound), delete.execute(DeleteAddressUc.Cmd(mina, sarasAddress)))
        assertEquals(Result.failure(SetDefaultAddressUc.Failure.AddressNotFound), setDefault.execute(SetDefaultAddressUc.Cmd(mina, sarasAddress)))
        assertEquals(1, books.stored[sara]!!.size)
    }
}
