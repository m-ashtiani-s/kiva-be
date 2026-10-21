package com.kiva.application.usecase.product.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductRef
import com.kiva.application.util.UnusedCategoryRepository
import com.kiva.application.util.UnusedColorRepository
import com.kiva.application.util.UnusedMaterialRepository
import com.kiva.application.util.UnusedProductQmRepository
import com.kiva.application.util.factory.CategoryFactory
import com.kiva.application.util.factory.ColorFactory
import com.kiva.application.util.factory.MaterialFactory
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.inventory.entity.Inventory
import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.domain.aggregate.inventory.repository.InventoryRepository
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.product.repository.ProductCodeTakenException
import com.kiva.domain.aggregate.product.repository.ProductIdGenerator
import com.kiva.domain.aggregate.product.repository.ProductRepository
import java.time.Instant

// In-memory stand-ins shared by the product command tests.

/** Products kept in memory as their events, like the real repository. */
internal class InMemoryProducts : ProductRepository {
    val streams = mutableMapOf<ProductId, List<ProductDomainEvent>>()

    override fun get(id: ProductId): Product? = streams[id]?.let { Product(it) }

    override fun save(product: Product) {
        streams[product.id] = streams[product.id].orEmpty() + product.getRecordedEvents()
    }
}

/** The save refused the way the database refuses it when another product took one of the codes a moment earlier. */
internal class TakenOnSave(products: ProductRepository, private val taken: (Product) -> ProductCodeTakenException) :
    ProductRepository by products {
    override fun save(product: Product) = throw taken(product)
}

/** Ids from 1, like the database sequences on an empty database. */
internal class CountingIds : ProductIdGenerator {
    var productsTaken = 0L
    var variantsTaken = 100L

    override fun nextProductId(): ProductId = ProductId.constructFromPersisted(++productsTaken)

    override fun nextVariantId(): VariantId = VariantId.constructFromPersisted(++variantsTaken)
}

/** The read model as far as creating a product needs it: the slugs and codes already in use. */
internal class ReadModel(val takenSlugs: Set<String> = emptySet(), val takenSkus: Set<String> = emptySet()) : UnusedProductQmRepository() {
    override fun findDetail(ref: ProductRef): ProductDetailQm? = error("not used here")

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? = error("not used here")

    override fun isSlugTaken(slug: Slug): Boolean = slug.value in takenSlugs

    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = skus.filter { it.value in takenSkus }.toSet()

    override fun countActiveByCategory(): Map<Slug, Int> = error("not used here")

    override fun countActiveByColor(): Map<ColorKey, Int> = error("not used here")
}

/** Stock kept in memory as events. */
internal class InMemoryInventories : InventoryRepository {
    val streams = mutableMapOf<VariantId, List<InventoryDomainEvent>>()

    override fun get(variantId: VariantId): Inventory? = streams[variantId]?.let { Inventory(it) }

    override fun save(inventory: Inventory) {
        streams[inventory.variantId] = streams[inventory.variantId].orEmpty() + inventory.getRecordedEvents()
    }
}

/** Uploaded files kept in memory; [attached] says which ones were marked used. */
internal class InMemoryMedia : MediaRepository {
    val rows = mutableMapOf<MediaId, MediaAsset>()
    val attached = mutableSetOf<MediaId>()

    fun upload(n: Int, purpose: MediaPurpose = MediaPurpose.PRODUCT, type: MediaType = MediaType.IMAGE): MediaId {
        val id = mediaId(n)
        rows[id] = MediaAsset.upload(
            id, purpose, type, 1600, 1200, if (type == MediaType.VIDEO) 12 else null, 1000, null,
            listOf(MediaVariant.of(320, "p/$n/320.webp"), MediaVariant.of(1600, "p/$n/1600.webp")),
            if (type == MediaType.VIDEO) "p/$n/poster.webp" else null, null, "p/$n", UserId.SYSTEM,
            Instant.parse("2026-10-01T00:00:00Z"),
        )
        return id
    }

    override fun find(id: MediaId): MediaAsset? = rows[id]

    override fun findAll(ids: Collection<MediaId>): List<MediaAsset> = ids.mapNotNull { rows[it] }

    override fun insert(asset: MediaAsset) {
        rows[asset.id] = asset
    }

    override fun markAttached(ids: Collection<MediaId>, now: Instant): Int {
        attached += ids
        return ids.size
    }

    override fun findUnattachedBefore(before: Instant, limit: Int): List<MediaId> = error("not used here")

    override fun deleteIfUnattached(id: MediaId, before: Instant): Boolean = error("not used here")

    override fun uploadTimesOf(userId: UserId, since: Instant): List<Instant> = error("not used here")

    override fun lockUploadsOf(userId: UserId) = error("not used here")

    companion object {
        fun mediaId(n: Int): MediaId = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, '0'))
    }
}

/** Keeps every change log row written. */
internal class RecordingAuditTrail : AuditTrail {
    val recorded = mutableListOf<AuditEntry>()

    override fun record(entry: AuditEntry) {
        recorded += entry
    }

    override fun deleteRecordedBefore(before: Instant, limit: Int): Int = 0
}

/** The shoulder category (1), satin (11), and a palette of lilac, black and cream. */
internal object Catalog {
    val categories = object : UnusedCategoryRepository() {
        private val shoulder = CategoryFactory.generate(id = 1, slug = "shoulder")
        override fun findAll(): List<Category> = listOf(shoulder)
        override fun findBySlug(slug: Slug): Category? = shoulder.takeIf { it.slug == slug }
        override fun findById(id: CategoryId): Category? = shoulder.takeIf { it.id == id }
    }
    val materials = object : UnusedMaterialRepository() {
        private val satin = MaterialFactory.generate(id = 11, slug = "satin", name = "ساتن")
        override fun findAll(): List<Material> = listOf(satin)
        override fun findById(id: MaterialId): Material? = satin.takeIf { it.id == id }
    }
    val colors = object : UnusedColorRepository() {
        private val palette = listOf("lilac", "black", "cream").map { ColorFactory.generate(key = it) }
        override fun findAll(): List<Color> = palette
        override fun findByKey(key: ColorKey): Color? = palette.firstOrNull { it.key == key }
    }
}
