package com.kiva.domain.aggregate.address

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressIdGenerator
import com.kiva.domain.aggregate.geo.entity.City
import com.kiva.domain.aggregate.geo.entity.Province
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import java.time.Instant

/**
 * What an [AddressBook] can't know by itself: whether the province and city of an address exist and go together (the
 * city list), and the next address number. Never saves and never opens a transaction; the use case does both.
 * Removing an address and changing the default need neither, so use cases call those on the book directly.
 */
class AddressBookDomainService(
    private val geoRepository: GeoRepository,
    private val addressIdGenerator: AddressIdGenerator,
) {
    fun addAddress(book: AddressBook, input: AddressInput, setAsDefault: Boolean, now: Instant): Result<Address, Failure> {
        placeOf(input).failureOrNull()?.let { return Result.failure(it) }
        return book.add(addressIdGenerator.nextAddressId(), input, setAsDefault, now).mapOnFailure { Failure.Book(it) }
    }

    /** An address that isn't in the book is ADDRESS_NOT_FOUND before anything about the place is checked. */
    fun updateAddress(book: AddressBook, id: AddressId, input: AddressInput, setAsDefault: Boolean, now: Instant): Result<Address, Failure> {
        if (book.find(id) == null) return Result.failure(Failure.Book(AddressBook.Failure.AddressNotFound))
        placeOf(input).failureOrNull()?.let { return Result.failure(it) }
        return book.update(id, input, setAsDefault, now).mapOnFailure { Failure.Book(it) }
    }

    /**
     * The province and city of [input], which must exist and go together, whether the address is kept in a book or only
     * sent with an order. A province or city that doesn't exist is a wrong input (400 on its field); a real city of
     * another province is 422.
     */
    fun placeOf(input: AddressInput): Result<Place, Failure> {
        val province = geoRepository.findProvince(input.provinceId) ?: return Result.failure(Failure.ProvinceNotFound)
        val city = geoRepository.findCity(input.cityId) ?: return Result.failure(Failure.CityNotFound)
        return if (city.provinceId == input.provinceId) Result.success(Place(province, city)) else Result.failure(Failure.CityNotInProvince)
    }

    /** Where an address is: its province and its city, which belongs to it. */
    data class Place(val province: Province, val city: City)

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data object ProvinceNotFound : Failure("PROVINCE_NOT_FOUND", FailureKind.INVALID, "provinceId")

        data object CityNotFound : Failure("CITY_NOT_FOUND", FailureKind.INVALID, "cityId")

        data object CityNotInProvince : Failure("CITY_NOT_IN_PROVINCE", FailureKind.BUSINESS_RULE)

        /** A rule of the book itself: the address isn't there, or the book is full. */
        data class Book(val failure: AddressBook.Failure) : Failure(failure.code, failure.kind, meta = failure.meta)
    }
}
