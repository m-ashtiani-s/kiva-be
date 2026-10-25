package com.kiva.application.usecase.order

import com.kiva.application.repository.OfferQm
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku

/**
 * What an order line copies of a color from the catalog, the same way when an order is placed and when a change request
 * swaps a line for another color (Rule 15): what it is called and looks like now. A later rename or change of the gallery
 * never changes what was bought.
 */
object OrderItems {

    /** The width of the photo a line shows: its path is kept for that size. */
    const val IMAGE_WIDTH = 480

    /** The color of [qm] as an order line keeps it: its name, slug, SKU, palette color, and its first photo at [IMAGE_WIDTH]. */
    fun snapshotOf(qm: OfferQm): OrderItemSnapshot = OrderItemSnapshot(
        name = ProductName.constructFromPersisted(qm.product.name),
        slug = Slug.constructFromPersisted(qm.product.slug),
        sku = Sku.constructFromPersisted(qm.color.sku),
        color = OrderItemSnapshot.Color(ColorKey.constructFromPersisted(qm.color.key), qm.color.name, qm.color.hex),
        imageUrl = qm.image?.pathFor(IMAGE_WIDTH),
    )
}
