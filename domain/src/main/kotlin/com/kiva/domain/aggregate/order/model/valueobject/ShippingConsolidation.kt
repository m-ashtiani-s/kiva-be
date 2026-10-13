package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode

/**
 * Round 2: the order joins the shopper's held reservation group and ships with it. Only the group's [method] can be
 * chosen, and it costs nothing.
 */
data class ShippingConsolidation(val method: ShippingMethodCode)
