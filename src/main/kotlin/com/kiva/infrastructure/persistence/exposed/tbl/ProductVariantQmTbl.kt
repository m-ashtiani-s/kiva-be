package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductMediaPayload
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.json.jsonb

/**
 * One row per color of a product: its price, gallery and stock. Written only by the projectors. Created by
 * V5__product_read_model.sql (Exposed never creates tables); keep both in step.
 *
 * [price] and [compareAtPrice] are what the color costs: its own price tag when [hasOwnPrice], otherwise a copy of the
 * product's. [onHand] and [reserved] come from the stock events.
 */
object ProductVariantQmTbl : LongIdTable(name = "productvariantqm_tbl", columnName = "pId") {
    val variantId = long("variantId").uniqueIndex()
    val productId = long("productId").references(ProductQmTbl.productId)
    val colorKey = varchar("colorKey", 31).references(ColorTbl.colorKey)
    val sku = varchar("sku", 80).uniqueIndex()
    val price = long("price")
    val compareAtPrice = long("compareAtPrice").nullable()
    val hasOwnPrice = bool("hasOwnPrice")
    val isDefault = bool("isDefault")
    val isActive = bool("isActive")
    val sortOrder = integer("sortOrder")
    val media = jsonb<List<ProductMediaPayload>>("media", Json)
    val onHand = integer("onHand").default(0)
    val reserved = integer("reserved").default(0)

    /** The unique key on [sku] as V5 named it; the database names it when it refuses a save. */
    const val SKU_KEY = "productvariantqm_tbl_sku_key"

    init {
        uniqueIndex("productvariantqm_tbl_product_color_key", productId, colorKey)
    }
}
