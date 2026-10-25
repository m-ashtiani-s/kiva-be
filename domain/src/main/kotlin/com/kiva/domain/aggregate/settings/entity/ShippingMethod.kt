package com.kiva.domain.aggregate.settings.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.TrackingUrlTemplate

/**
 * One way the shop ships (تیپاکس، پست معمولی), as the shop has set it up. Part of [ShopSettings], which changes it.
 *
 * Its identity is [code].
 *
 * @property baseCost the price before any free shipping (shown crossed out when the order ships free).
 * @property icon an icon of the frontend's set (contract IconName: `truck`, `box`).
 * @property isActive offered to shoppers; an inactive method keeps its row so it can be switched back on.
 * @property sortOrder position among the options, smallest first.
 * @property trackingUrlTemplate where a parcel of this method is followed; null when the carrier has no such page.
 */
class ShippingMethod private constructor(
    val code: ShippingMethodCode,
    val name: String,
    val baseCost: Money,
    val deliveryDays: DeliveryDays,
    val icon: String,
    val isActive: Boolean,
    val sortOrder: Int,
    val trackingUrlTemplate: TrackingUrlTemplate?,
) {
    init {
        require(name.isNotBlank() && icon.isNotBlank()) { "A shipping method needs a name and an icon ($code)" }
        require(baseCost.toman <= ShopSettings.MAX_SHIPPING_COST) { "A shipping cost of $baseCost ($code)" }
    }

    override fun equals(other: Any?): Boolean = other is ShippingMethod && other.code == code

    override fun hashCode(): Int = code.hashCode()

    /** The page that follows the parcel [trackingCode], when the carrier has one. */
    fun trackingUrlOf(trackingCode: String): String? = trackingUrlTemplate?.urlOf(trackingCode)

    /** Every value the shop can change is the same as [other]'s (the identity compares only [code]). */
    fun sameValuesAs(other: ShippingMethod): Boolean =
        code == other.code && name == other.name && baseCost == other.baseCost && deliveryDays == other.deliveryDays && icon == other.icon &&
            isActive == other.isActive && sortOrder == other.sortOrder && trackingUrlTemplate == other.trackingUrlTemplate

    override fun toString(): String = "ShippingMethod(code=$code, isActive=$isActive)"

    companion object {
        fun constructFromPersisted(
            code: ShippingMethodCode,
            name: String,
            baseCost: Money,
            deliveryDays: DeliveryDays,
            icon: String,
            isActive: Boolean,
            sortOrder: Int,
            trackingUrlTemplate: TrackingUrlTemplate? = null,
        ): ShippingMethod = ShippingMethod(code, name, baseCost, deliveryDays, icon, isActive, sortOrder, trackingUrlTemplate)

        /** Only ShopSettings.change makes a changed one. */
        internal fun of(draft: ShopSettings.ShippingMethodDraft, sortOrder: Int): ShippingMethod = ShippingMethod(
            draft.code, draft.name.value, draft.baseCost, draft.deliveryDays, draft.icon.value, draft.isActive, sortOrder, draft.trackingUrlTemplate,
        )
    }
}
