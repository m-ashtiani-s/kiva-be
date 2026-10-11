package com.kiva.domain.aggregate.address.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.AddressTitle
import com.kiva.domain.aggregate.address.model.valueobject.GeoPoint
import com.kiva.domain.aggregate.address.model.valueobject.Plaque
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.address.model.valueobject.UnitNumber
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId

/**
 * An address as the customer filled it in (the contract's AddressInput), every field checked: the same for adding an
 * address, editing one and a new address typed at checkout. Whether the province and city exist and go together is
 * the domain service's to check, against the city list.
 *
 * @property isSelfRecipient «گیرنده خودم هستم»: only how the form was filled in; the recipient's name and phone are
 *   always given.
 */
class AddressInput private constructor(
    val title: AddressTitle?,
    val provinceId: ProvinceId,
    val cityId: CityId,
    val line: AddressLine,
    val plaque: Plaque?,
    val unit: UnitNumber?,
    val postalCode: PostalCode,
    val recipientName: RecipientName,
    val recipientPhone: IranianMobileNumber,
    val isSelfRecipient: Boolean,
    val location: GeoPoint?,
) {
    override fun toString(): String = "AddressInput(provinceId=${provinceId.value}, cityId=${cityId.value})"

    companion object {
        /**
         * Every field at once, so the form shows all its mistakes together, each on its own field. Missing values are
         * null: the contract's required ones then fail like an empty value would.
         */
        fun makeNew(
            title: String?,
            provinceId: Int?,
            cityId: Int?,
            addressLine: String?,
            plaque: String?,
            unit: String?,
            postalCode: String?,
            recipientName: String?,
            recipientPhone: String?,
            isSelfRecipient: Boolean?,
            lat: Double?,
            lng: Double?,
        ): Result<AddressInput, Invalid> {
            val problems = mutableListOf<ResultFailure>()
            fun <T> take(result: Result<T, ResultFailure>): T? {
                result.failureOrNull()?.let { problems += it }
                return result.getOrNull()
            }

            val checkedTitle = take(AddressTitle.makeNew(title))
            val checkedProvince = take(
                provinceId?.let { ProvinceId.makeNew(it).forField("provinceId") } ?: Result.failure(Failure.ProvinceRequired),
            )
            val checkedCity = take(cityId?.let { CityId.makeNew(it).forField("cityId") } ?: Result.failure(Failure.CityRequired))
            val checkedLine = take(AddressLine.makeNew(addressLine.orEmpty()))
            val checkedPlaque = take(Plaque.makeNew(plaque))
            val checkedUnit = take(UnitNumber.makeNew(unit))
            val checkedPostalCode = take(PostalCode.makeNew(postalCode.orEmpty()))
            val checkedName = take(RecipientName.makeNew(recipientName.orEmpty()))
            val checkedPhone = take(IranianMobileNumber.makeNew(recipientPhone.orEmpty()).forField("recipientPhone"))
            val checkedLocation = take(GeoPoint.makeNew(lat, lng))

            if (problems.isNotEmpty()) return Result.failure(Invalid(problems))
            return Result.success(
                AddressInput(
                    checkedTitle, checkedProvince!!, checkedCity!!, checkedLine!!, checkedPlaque, checkedUnit, checkedPostalCode!!,
                    checkedName!!, checkedPhone!!, isSelfRecipient ?: false, checkedLocation,
                ),
            )
        }
    }

    /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
    data class Invalid(val problems: List<ResultFailure>) : ResultFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

    sealed class Failure(code: String, field: String) : ResultFailure(code, FailureKind.INVALID, field = field) {
        data object ProvinceRequired : Failure("PROVINCE_REQUIRED", "provinceId")
        data object CityRequired : Failure("CITY_REQUIRED", "cityId")
    }
}
