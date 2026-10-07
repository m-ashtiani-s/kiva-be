package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.Highlights
import com.kiva.domain.aggregate.product.model.valueobject.MaterialDescription
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductDescription
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.ShortDescription
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.ZoneOffset

/** Reads productqm_tbl and productvariantqm_tbl. Runs inside the use case's transaction; it never opens one itself. */
class ProductQmRepositoryImpl : ProductQmRepository {

    // ============ the shop's list ============

    /**
     * One query on productqm_tbl for the page (every filter is a column of it), then one for the active colors of
     * just the products on that page.
     */
    override fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm> {
        val matching = ProductQmTbl.selectAll().where { conditionsOf(filter) }

        val page = if (filter.ids.isEmpty()) {
            matching.paginate(pageable, sortOrders, tiebreaker = ProductQmTbl.id) { it }
        } else {
            // At most 60 ids: every match is read, put in the order asked for, then cut to the page.
            val position = filter.ids.withIndex().associate { (index, id) -> id.value to index }
            val rows = matching.toList().sortedBy { position.getValue(it[ProductQmTbl.productId]) }
            val skipped = minOf(pageable.offset, rows.size.toLong()).toInt()
            Page(rows.drop(skipped).take(pageable.size), pageable.page, pageable.size, rows.size.toLong())
        }

        val colors = activeColorsOf(page.items.map { it[ProductQmTbl.productId] })
        return page.map { cardOf(it, colors[it[ProductQmTbl.productId]].orEmpty()) }
    }

    /** Only products in the shop, whatever else is asked; each filter given narrows further. */
    private fun conditionsOf(filter: ProductFilter): Op<Boolean> {
        val conditions = buildList<Op<Boolean>> {
            add(ProductQmTbl.status eq ProductStatus.ACTIVE)
            // Every word somewhere in the text; lower() so a Latin word matches in either case.
            filter.words.forEach { word -> add(ProductQmTbl.searchText.lowerCase() like containing(word)) }
            if (filter.categories.isNotEmpty()) add(ProductQmTbl.categorySlug inList filter.categories.map { it.value })
            if (filter.colors.isNotEmpty()) add(Overlaps(ProductQmTbl.colorKeys, filter.colors.map { it.value }))
            filter.price.min?.let { add(ProductQmTbl.price greaterEq it.toman) }
            filter.price.max?.let { add(ProductQmTbl.price lessEq it.toman) }
            if (filter.onSale) add(ProductQmTbl.discountPercent greater 0)
            if (filter.inStock) add(ProductQmTbl.inStock eq true)
            // A product without newUntil is never new: the comparison with NULL is not true.
            filter.newAt?.let { add(ProductQmTbl.newUntil greater it.atOffset(ZoneOffset.UTC)) }
            if (filter.hasVideo) add(ProductQmTbl.hasVideo eq true)
            if (filter.bagTypes.isNotEmpty()) add(ProductQmTbl.bagType inList filter.bagTypes)
            if (filter.materials.isNotEmpty()) add(ProductQmTbl.materialSlug inList filter.materials.map { it.value })
            if (filter.occasions.isNotEmpty()) add(Overlaps(ProductQmTbl.occasions, filter.occasions.map { it.name }))
            if (filter.ids.isNotEmpty()) add(ProductQmTbl.productId inList filter.ids.map { it.value })
        }
        return conditions.reduce { all, next -> all and next }
    }

    /** `%word%`, with any `%` or `_` the shopper typed taken as themselves. */
    private fun containing(word: String): LikePattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(word, ESCAPE) + "%"

    /** Postgres `&&`: the array column shares at least one element with [values]. */
    private class Overlaps(column: Column<List<String>>, values: List<String>) :
        ComparisonOp(column, QueryParameter(values, column.columnType), "&&")

    /** The active colors of these products, in display order, by product. */
    private fun activeColorsOf(productIds: List<Long>): Map<Long, List<ProductCardQm.Color>> {
        if (productIds.isEmpty()) return emptyMap()
        return ProductVariantQmTbl.selectAll()
            .where { (ProductVariantQmTbl.productId inList productIds) and (ProductVariantQmTbl.isActive eq true) }
            .orderBy(ProductVariantQmTbl.sortOrder to SortOrder.ASC, ProductVariantQmTbl.variantId to SortOrder.ASC)
            .toList()
            .groupBy({ it[ProductVariantQmTbl.productId] }) {
                ProductCardQm.Color(
                    variantId = VariantId.constructFromPersisted(it[ProductVariantQmTbl.variantId]),
                    colorKey = ColorKey.constructFromPersisted(it[ProductVariantQmTbl.colorKey]),
                    isDefault = it[ProductVariantQmTbl.isDefault],
                    available = it[ProductVariantQmTbl.onHand] - it[ProductVariantQmTbl.reserved],
                )
            }
    }

    private fun cardOf(row: ResultRow, colors: List<ProductCardQm.Color>): ProductCardQm = ProductCardQm(
        productId = ProductId.constructFromPersisted(row[ProductQmTbl.productId]),
        slug = Slug.constructFromPersisted(row[ProductQmTbl.slug]),
        sku = Sku.constructFromPersisted(row[ProductQmTbl.sku]),
        name = ProductName.constructFromPersisted(row[ProductQmTbl.name]),
        categorySlug = Slug.constructFromPersisted(row[ProductQmTbl.categorySlug]),
        bagType = row[ProductQmTbl.bagType],
        priceTag = priceTagOf(row[ProductQmTbl.price], row[ProductQmTbl.compareAtPrice]),
        hasVideo = row[ProductQmTbl.hasVideo],
        soldCount = row[ProductQmTbl.soldCount],
        newUntil = row[ProductQmTbl.newUntil]?.toInstant(),
        maxPerOrder = MaxPerOrder.constructFromPersisted(row[ProductQmTbl.maxPerOrder]),
        colors = colors,
    )

    private fun priceTagOf(price: Long, compareAtPrice: Long?): PriceTag =
        PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAtPrice?.let { Money.constructFromPersisted(it) })

    // ============ one product's page ============

    override fun findDetail(ref: ProductRef): ProductDetailQm? {
        val row = ProductQmTbl.selectAll()
            .where { (ProductQmTbl.status eq ProductStatus.ACTIVE) and matching(ref) }
            .singleOrNull()
            ?: return null

        val variants = ProductVariantQmTbl.selectAll()
            .where { (ProductVariantQmTbl.productId eq row[ProductQmTbl.productId]) and (ProductVariantQmTbl.isActive eq true) }
            .orderBy(ProductVariantQmTbl.sortOrder to SortOrder.ASC, ProductVariantQmTbl.variantId to SortOrder.ASC)
            .map {
                ProductDetailQm.Variant(
                    variantId = VariantId.constructFromPersisted(it[ProductVariantQmTbl.variantId]),
                    colorKey = ColorKey.constructFromPersisted(it[ProductVariantQmTbl.colorKey]),
                    sku = Sku.constructFromPersisted(it[ProductVariantQmTbl.sku]),
                    priceTag = priceTagOf(it[ProductVariantQmTbl.price], it[ProductVariantQmTbl.compareAtPrice]),
                    isDefault = it[ProductVariantQmTbl.isDefault],
                    available = it[ProductVariantQmTbl.onHand] - it[ProductVariantQmTbl.reserved],
                    media = it[ProductVariantQmTbl.media].map { media -> media.toDomain() },
                )
            }

        return ProductDetailQm(
            card = cardOf(row, variants.map { ProductCardQm.Color(it.variantId, it.colorKey, it.isDefault, it.available) }),
            materialSlug = Slug.constructFromPersisted(row[ProductQmTbl.materialSlug]),
            materialDescription = row[ProductQmTbl.materialDescription]?.let { MaterialDescription.constructFromPersisted(it) },
            shortDescription = row[ProductQmTbl.shortDescription]?.let { ShortDescription.constructFromPersisted(it) },
            description = row[ProductQmTbl.description]?.let { ProductDescription.constructFromPersisted(it) },
            highlights = Highlights.constructFromPersisted(row[ProductQmTbl.highlights]),
            specs = row[ProductQmTbl.specs].toDomain(),
            returnable = row[ProductQmTbl.returnable],
            reservable = row[ProductQmTbl.reservable],
            publishedOn = row[ProductQmTbl.publishedOn]?.toInstant(),
            updatedOn = row[ProductQmTbl.updatedOn].toInstant(),
            variants = variants,
        )
    }

    private fun matching(ref: ProductRef): Op<Boolean> = when (ref) {
        is ProductRef.ById -> ProductQmTbl.productId eq ref.id.value
        is ProductRef.BySku -> ProductQmTbl.sku eq ref.sku.value
        is ProductRef.BySlug -> ProductQmTbl.slug eq ref.slug.value
    }

    // ============ one color, slugs and codes in use ============

    override fun findVariant(variantId: VariantId): VariantQm? =
        ProductVariantQmTbl.selectAll()
            .where { ProductVariantQmTbl.variantId eq variantId.value }
            .singleOrNull()
            ?.let {
                VariantQm(
                    variantId = VariantId.constructFromPersisted(it[ProductVariantQmTbl.variantId]),
                    productId = ProductId.constructFromPersisted(it[ProductVariantQmTbl.productId]),
                    colorKey = ColorKey.constructFromPersisted(it[ProductVariantQmTbl.colorKey]),
                    sku = Sku.constructFromPersisted(it[ProductVariantQmTbl.sku]),
                    isActive = it[ProductVariantQmTbl.isActive],
                )
            }

    override fun isSlugTaken(slug: Slug): Boolean =
        !ProductQmTbl.select(ProductQmTbl.id).where { ProductQmTbl.slug eq slug.value }.empty()

    // A code is unique across products and their colors: KV-1001 can't be one product's and another's color's.
    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> {
        if (skus.isEmpty()) return emptySet()
        val codes = skus.map { it.value }
        val ofProducts = ProductQmTbl.select(ProductQmTbl.sku).where { ProductQmTbl.sku inList codes }.map { it[ProductQmTbl.sku] }
        val ofColors = ProductVariantQmTbl.select(ProductVariantQmTbl.sku)
            .where { ProductVariantQmTbl.sku inList codes }
            .map { it[ProductVariantQmTbl.sku] }
        return (ofProducts + ofColors).map { Sku.constructFromPersisted(it) }.toSet()
    }

    // ============ related products ============

    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? {
        val category = ProductQmTbl.select(ProductQmTbl.categorySlug)
            .where { (ProductQmTbl.productId eq productId.value) and (ProductQmTbl.status eq ProductStatus.ACTIVE) }
            .singleOrNull()
            ?.get(ProductQmTbl.categorySlug)
            ?: return null

        val rows = ProductQmTbl.selectAll()
            .where { (ProductQmTbl.status eq ProductStatus.ACTIVE) and (ProductQmTbl.productId neq productId.value) }
            .orderBy(
                // Same category first (true sorts after false, so DESC), then the best selling; on a tie, what can be bought.
                (ProductQmTbl.categorySlug eq category) to SortOrder.DESC,
                ProductQmTbl.soldCount to SortOrder.DESC,
                ProductQmTbl.inStock to SortOrder.DESC,
                ProductQmTbl.id to SortOrder.ASC,
            )
            .limit(limit)
            .toList()

        val colors = activeColorsOf(rows.map { it[ProductQmTbl.productId] })
        return rows.map { cardOf(it, colors[it[ProductQmTbl.productId]].orEmpty()) }
    }

    // ============ counts for the menus and filters ============

    /** Each filter counted without its own value, so the other values of a picked filter still show their counts. */
    override fun facets(filter: ProductFilter, now: Instant): ProductFacetsQm {
        val prices = filter.copy(price = PriceRange.ANY)
        val cheapest = ProductQmTbl.price.min()
        val dearest = ProductQmTbl.price.max()
        val range = ProductQmTbl.select(cheapest, dearest).where { conditionsOf(prices) }.single()

        return ProductFacetsQm(
            categories = countBy(ProductQmTbl.categorySlug, filter.copy(categories = emptySet())).mapKeys { Slug.constructFromPersisted(it.key) },
            colors = colorCounts(filter.copy(colors = emptySet())),
            materials = countBy(ProductQmTbl.materialSlug, filter.copy(materials = emptySet())).mapKeys { Slug.constructFromPersisted(it.key) },
            bagTypes = countBy(ProductQmTbl.bagType, filter.copy(bagTypes = emptySet())),
            minPrice = range[cheapest]?.let { Money.constructFromPersisted(it) },
            maxPrice = range[dearest]?.let { Money.constructFromPersisted(it) },
            onSale = countOf(filter.copy(onSale = false), ProductQmTbl.discountPercent greater 0),
            inStock = countOf(filter.copy(inStock = false), ProductQmTbl.inStock eq true),
            new = countOf(filter.copy(newAt = null), ProductQmTbl.newUntil greater now.atOffset(ZoneOffset.UTC)),
            withVideo = countOf(filter.copy(hasVideo = false), ProductQmTbl.hasVideo eq true),
        )
    }

    override fun countActiveByCategory(): Map<Slug, Int> =
        countBy(ProductQmTbl.categorySlug, ProductFilter()).mapKeys { Slug.constructFromPersisted(it.key) }

    override fun countActiveByMaterial(): Map<Slug, Int> =
        countBy(ProductQmTbl.materialSlug, ProductFilter()).mapKeys { Slug.constructFromPersisted(it.key) }

    override fun countActiveByColor(): Map<ColorKey, Int> = colorCounts(ProductFilter())

    /** How many products pass [filter], for each value of [column] they have. */
    private fun <T> countBy(column: Column<T>, filter: ProductFilter): Map<T, Int> {
        val products = ProductQmTbl.id.count()
        return ProductQmTbl.select(column, products)
            .where { conditionsOf(filter) }
            .groupBy(column)
            .associate { it[column] to it[products].toInt() }
    }

    // A product counts once for each of its active colors. Counted here rather than with unnest in SQL, so it can take
    // the same conditions as everything else; the shop has hundreds of products, not millions.
    private fun colorCounts(filter: ProductFilter): Map<ColorKey, Int> =
        ProductQmTbl.select(ProductQmTbl.colorKeys)
            .where { conditionsOf(filter) }
            .flatMap { it[ProductQmTbl.colorKeys] }
            .groupingBy { ColorKey.constructFromPersisted(it) }
            .eachCount()

    private fun countOf(filter: ProductFilter, also: Op<Boolean>): Int =
        ProductQmTbl.selectAll().where { conditionsOf(filter) and also }.count().toInt()

    companion object {
        private const val ESCAPE = '\\'

        /**
         * What each of ProductSorts means here. Every order but the price ones starts with what can be bought
         * (ProductSorts.putsSoldOutLast). paginate adds pId last, so rows that tie keep one order across pages.
         */
        val sortOrders: SortOrders = ProductSorts.names.associateWith { sort ->
            val own: Pair<Expression<*>, SortOrder> = when (sort) {
                ProductSorts.NEWEST -> ProductQmTbl.createdOn to SortOrder.DESC
                ProductSorts.BESTSELLING -> ProductQmTbl.soldCount to SortOrder.DESC
                ProductSorts.PRICE_ASC -> ProductQmTbl.price to SortOrder.ASC
                ProductSorts.PRICE_DESC -> ProductQmTbl.price to SortOrder.DESC
                ProductSorts.DISCOUNT_DESC -> ProductQmTbl.discountPercent to SortOrder.DESC
                ProductSorts.RATING_DESC -> ProductQmTbl.ratingAvg to SortOrder.DESC
                else -> error("No database order for sort '$sort'")
            }
            if (ProductSorts.putsSoldOutLast(sort)) listOf(ProductQmTbl.inStock to SortOrder.DESC, own) else listOf(own)
        }
    }
}
