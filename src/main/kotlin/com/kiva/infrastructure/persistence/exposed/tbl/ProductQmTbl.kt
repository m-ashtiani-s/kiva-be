package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.infrastructure.persistence.event.serializer.payload.product.ProductSpecsPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * One row per product, ready for the shop's lists and filters. Written only by the projectors. Created by
 * V5__product_read_model.sql and the migrations after it (Exposed never creates tables); keep them in step.
 *
 * [price], [compareAtPrice] and [discountPercent] are the card's (the default color's); [basePrice] and
 * [baseCompareAtPrice] are the product's own, which colors without their own price follow.
 */
object ProductQmTbl : LongIdTable(name = "productqm_tbl", columnName = "pId") {
    val productId = long("productId").uniqueIndex()
    val slug = varchar("slug", 100).uniqueIndex()
    val sku = varchar("sku", 80).uniqueIndex()
    val name = varchar("name", 120)
    val searchText = text("searchText")
    val categorySlug = varchar("categorySlug", 100).references(CategoryTbl.slug)
    val materialSlug = varchar("materialSlug", 100).references(MaterialTbl.slug)
    val bagType = enumerationByName<BagType>("bagType", 20)
    val basePrice = long("basePrice")
    val baseCompareAtPrice = long("baseCompareAtPrice").nullable()
    val price = long("price")
    val compareAtPrice = long("compareAtPrice").nullable()
    val discountPercent = integer("discountPercent")
    val colorKeys = array<String>("colorKeys")
    val inStock = bool("inStock")
    val hasVideo = bool("hasVideo")
    val soldCount = integer("soldCount").default(0)
    val newUntil = timestampWithTimeZone("newUntil").nullable()
    val sortPriority = integer("sortPriority")
    val occasions = array<String>("occasions")
    val maxPerOrder = integer("maxPerOrder")
    val returnable = bool("returnable")
    val reservable = bool("reservable")
    val shortDescription = varchar("shortDescription", 300).nullable()
    val description = text("description").nullable()
    val highlights = array<String>("highlights")
    val materialDescription = varchar("materialDescription", 120).nullable()
    val specs = jsonb<ProductSpecsPayload>("specs", Json)
    val status = enumerationByName<ProductStatus>("status", 10)
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
    val publishedOn = timestampWithTimeZone("publishedOn").nullable()
    /** The first time it went into the shop; «newest» is by this one, so going out and back in doesn't make it new. */
    val firstPublishedOn = timestampWithTimeZone("firstPublishedOn").nullable()

    // V24 (step 1.4): what the panel reads.
    /** The sequence of the product's last event: the ETag of the panel's form. */
    val version = long("version").default(1)
    /** Who made the product; 0 is the system. */
    val createdByUserId = long("createdByUserId").default(0)
    /** A draft's scheduled publication (step 1.6). */
    val publishAt = timestampWithTimeZone("publishAt").nullable()
    val tags = array<String>("tags").default(emptyList())
    val seo = jsonb<JsonObject>("seo", Json).default(JsonObject(emptyMap()))
    /** What the active colors can still sell together (on hand minus reserved). */
    val availableTotal = integer("availableTotal").default(0)

    /** The unique keys on [slug] and [sku] as V5 named them; the database names one when it refuses a save. */
    const val SLUG_KEY = "productqm_tbl_slug_key"
    const val SKU_KEY = "productqm_tbl_sku_key"
}
