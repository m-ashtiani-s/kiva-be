package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.dto.GalleryItemDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.AdminProductDetailQm
import com.kiva.application.repository.AdminProductFilter
import com.kiva.application.repository.AdminProductQm
import com.kiva.application.repository.ProductBriefQm
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.domain.aggregate.campaign.model.policy.CampaignShowcasePolicy
import com.kiva.application.repository.ProductQmRepository.NamedInSearch
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.application.usecase.product.usecase.query.AdminProductSorts
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
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
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.persistence.exposed.projector.ProductQmSummary
import com.kiva.infrastructure.common.persistence.paginate
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.core.upperCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.exists
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

    /** The products' ratings live apart (productrating_tbl, phase 8); read with the cards, one query a page. */
    private val ratings = ProductRatingRepositoryImpl()

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

        val ids = page.items.map { it[ProductQmTbl.productId] }
        val colors = activeColorsOf(ids)
        val ratings = ratingsOf(ids)
        return page.map { cardOf(it, colors[it[ProductQmTbl.productId]].orEmpty(), ratings[it[ProductQmTbl.productId]]) }
    }

    /** Three reads whatever the number: the rows, their active colors, their ratings. */
    override fun findCards(ids: List<ProductId>): List<ProductCardQm> {
        if (ids.isEmpty()) return emptyList()
        val position = ids.withIndex().associate { (index, id) -> id.value to index }
        val rows = ProductQmTbl.selectAll()
            .where { (ProductQmTbl.productId inList position.keys) and (ProductQmTbl.status eq ProductStatus.ACTIVE) }
            .sortedBy { position.getValue(it[ProductQmTbl.productId]) }
        val found = rows.map { it[ProductQmTbl.productId] }
        val colors = activeColorsOf(found)
        val ratings = ratingsOf(found)
        return rows.map { cardOf(it, colors[it[ProductQmTbl.productId]].orEmpty(), ratings[it[ProductQmTbl.productId]]) }
    }

    override fun campaignShowcase(productIds: Set<ProductId>?, limit: Int): List<ProductCardQm> {
        if (productIds != null && productIds.isEmpty()) return emptyList()
        val rows = ProductQmTbl.selectAll().where {
            val shown = (ProductQmTbl.status eq ProductStatus.ACTIVE) and
                (ProductQmTbl.discountPercent greaterEq CampaignShowcasePolicy.MIN_DISCOUNT_PERCENT) and (ProductQmTbl.inStock eq true)
            if (productIds == null) shown else shown and (ProductQmTbl.productId inList productIds.map { it.value })
        }.orderBy(ProductQmTbl.discountPercent to SortOrder.DESC, ProductQmTbl.soldCount to SortOrder.DESC, ProductQmTbl.productId to SortOrder.ASC)
            .limit(limit).toList()
        val ids = rows.map { it[ProductQmTbl.productId] }
        val colors = activeColorsOf(ids)
        val ratings = ratingsOf(ids)
        return rows.map { cardOf(it, colors[it[ProductQmTbl.productId]].orEmpty(), ratings[it[ProductQmTbl.productId]]) }
    }

    // Every match is read (a few columns; a shop of hundreds), ranked here, then the first ones' cards: four reads in all.
    override fun suggest(words: List<String>, limit: Int): ProductQmRepository.SuggestPage {
        val matches = ProductQmTbl.select(ProductQmTbl.productId, ProductQmTbl.searchName, ProductQmTbl.inStock, ProductQmTbl.soldCount)
            .where { conditionsOf(ProductFilter(words = words)) }
            .toList()
        val ranked = matches.sortedWith(
            compareByDescending<ResultRow> { row -> words.all { row[ProductQmTbl.searchName].lowercase().contains(it) } }
                .thenByDescending { it[ProductQmTbl.inStock] }
                .thenByDescending { it[ProductQmTbl.soldCount] }
                .thenByDescending { it[ProductQmTbl.productId] },
        )
        val first = ranked.take(limit).map { ProductId.constructFromPersisted(it[ProductQmTbl.productId]) }
        return ProductQmRepository.SuggestPage(findCards(first), matches.size)
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
            // A campaign that isn't running gives nothing: an id no product has.
            filter.campaignProductIds?.let { ids -> add(ProductQmTbl.productId inList ids.map { it.value }.ifEmpty { listOf(0L) }) }
        }
        return conditions.reduce { all, next -> all and next }
    }

    /** `%word%`, with any `%` or `_` the shopper typed taken as themselves. */
    private fun containing(word: String): LikePattern = LikePattern("%", ESCAPE) + LikePattern.ofLiteral(word, ESCAPE) + "%"

    /** Postgres `&&`: the array column shares at least one element with [values]. */
    private class Overlaps(column: Column<List<String>>, values: List<String>) :
        ComparisonOp(column, QueryParameter(values, column.columnType), "&&")

    /**
     * A color's first two photos, for its card: already in its row (the gallery's copy of each file), so a page of cards
     * reads no more than its colors' rows.
     */
    private fun cardPhotosOf(color: ResultRow): List<UploadedMediaDto> =
        color[ProductVariantQmTbl.media].filterNot { it.isVideo }.take(2).map { it.toDto().media }

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
                    price = Money.constructFromPersisted(it[ProductVariantQmTbl.price]),
                    photos = cardPhotosOf(it),
                )
            }
    }

    /** The ratings of [productIds] that have one, in one read (phase 8). */
    private fun ratingsOf(productIds: List<Long>): Map<Long, RatingTally> =
        ratings.findAll(productIds.map { ProductId.constructFromPersisted(it) }).mapKeys { it.key.value }

    private fun cardOf(row: ResultRow, colors: List<ProductCardQm.Color>, rating: RatingTally? = null): ProductCardQm = ProductCardQm(
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
        rating = rating ?: RatingTally.NONE,
    )

    private fun priceTagOf(price: Long, compareAtPrice: Long?): PriceTag =
        PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAtPrice?.let { Money.constructFromPersisted(it) })

    /** V24's `seo` JSONB back to the form's fields: only those set are in it (none is `{}`). */
    private fun seoOf(seo: JsonObject): SeoMeta = SeoMeta.constructFromPersisted(
        title = seo["title"]?.jsonPrimitive?.contentOrNull,
        description = seo["description"]?.jsonPrimitive?.contentOrNull,
        noIndex = seo["noIndex"]?.jsonPrimitive?.booleanOrNull ?: false,
    )

    // ============ one product's page ============

    override fun findDetail(ref: ProductRef): ProductDetailQm? {
        val row = ProductQmTbl.selectAll()
            .where { (ProductQmTbl.status eq ProductStatus.ACTIVE) and matching(ref) }
            .singleOrNull()
            ?: return null
        val activeColors = ProductVariantQmTbl.selectAll()
            .where { (ProductVariantQmTbl.productId eq row[ProductQmTbl.productId]) and (ProductVariantQmTbl.isActive eq true) }
            .orderBy(ProductVariantQmTbl.sortOrder to SortOrder.ASC, ProductVariantQmTbl.variantId to SortOrder.ASC)
            .toList()
        return detailOf(row, activeColors)
    }

    /** One product's page from its row and its active colors' rows (in display order). */
    private fun detailOf(row: ResultRow, activeColors: List<ResultRow>): ProductDetailQm {
        val variants = activeColors.map {
            ProductDetailQm.Variant(
                variantId = VariantId.constructFromPersisted(it[ProductVariantQmTbl.variantId]),
                colorKey = ColorKey.constructFromPersisted(it[ProductVariantQmTbl.colorKey]),
                sku = Sku.constructFromPersisted(it[ProductVariantQmTbl.sku]),
                priceTag = priceTagOf(it[ProductVariantQmTbl.price], it[ProductVariantQmTbl.compareAtPrice]),
                isDefault = it[ProductVariantQmTbl.isDefault],
                available = it[ProductVariantQmTbl.onHand] - it[ProductVariantQmTbl.reserved],
                media = it[ProductVariantQmTbl.media].map { media -> media.toDto() },
            )
        }

        return ProductDetailQm(
            card = cardOf(
                row,
                variants.zip(activeColors).map { (variant, color) ->
                    ProductCardQm.Color(variant.variantId, variant.colorKey, variant.isDefault, variant.available, variant.priceTag.price, cardPhotosOf(color))
                },
                ratings.find(ProductId.constructFromPersisted(row[ProductQmTbl.productId])),
            ),
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

    // ============ the panel ============

    /**
     * One query for the page's rows, each with its colors counted in SQL (all of them, and the active ones low on
     * stock), one for the total, and one for the first media of the page's default colors.
     */
    override fun findForAdmin(filter: AdminProductFilter, pageable: Pageable): Page<AdminProductQm> {
        val variantsCount = wrapAsExpression<Long>(
            ProductVariantQmTbl.select(ProductVariantQmTbl.id.count()).where { ProductVariantQmTbl.productId eq ProductQmTbl.productId },
        )
        val lowStockVariants = wrapAsExpression<Long>(
            ProductVariantQmTbl.select(ProductVariantQmTbl.id.count()).where { lowOnStock(filter.lowStockThreshold) },
        )
        val page = ProductQmTbl.select(ProductQmTbl.columns + variantsCount + lowStockVariants)
            .where { adminConditionsOf(filter) }
            .paginate(pageable, adminSortOrders, tiebreaker = ProductQmTbl.productId, tiebreakerOrder = SortOrder.DESC) { it }

        val media = defaultMediaOf(page.items.map { it[ProductQmTbl.productId] })
        val ratings = ratingsOf(page.items.map { it[ProductQmTbl.productId] })
        return page.map { row ->
            AdminProductQm(
                productId = ProductId.constructFromPersisted(row[ProductQmTbl.productId]),
                sku = Sku.constructFromPersisted(row[ProductQmTbl.sku]),
                name = ProductName.constructFromPersisted(row[ProductQmTbl.name]),
                categorySlug = Slug.constructFromPersisted(row[ProductQmTbl.categorySlug]),
                status = row[ProductQmTbl.status],
                priceTag = priceTagOf(row[ProductQmTbl.price], row[ProductQmTbl.compareAtPrice]),
                availableTotal = row[ProductQmTbl.availableTotal],
                variantsCount = (row[variantsCount] ?: 0L).toInt(),
                lowStockVariants = (row[lowStockVariants] ?: 0L).toInt(),
                soldCount = row[ProductQmTbl.soldCount],
                updatedOn = row[ProductQmTbl.updatedOn].toInstant(),
                defaultMedia = media[row[ProductQmTbl.productId]],
                rating = ratings[row[ProductQmTbl.productId]] ?: RatingTally.NONE,
            )
        }
    }

    /** Every status unless one is asked for; each filter given narrows further. */
    private fun adminConditionsOf(filter: AdminProductFilter): Op<Boolean> {
        val lowColor = ProductVariantQmTbl.select(ProductVariantQmTbl.id).where { lowOnStock(filter.lowStockThreshold) }
        val conditions = buildList<Op<Boolean>> {
            filter.q?.let { add(matchesAdminSearch(it)) }
            filter.status?.let { add(ProductQmTbl.status eq it) }
            if (filter.categories.isNotEmpty()) add(ProductQmTbl.categorySlug inList filter.categories.map { it.value })
            when (filter.stockStatus) {
                StockStatus.OUT_OF_STOCK -> add(ProductQmTbl.availableTotal eq 0)
                StockStatus.LOW_STOCK -> add((ProductQmTbl.availableTotal greater 0) and exists(lowColor))
                StockStatus.IN_STOCK -> add((ProductQmTbl.availableTotal greater 0) and notExists(lowColor))
                null -> Unit
            }
        }
        return conditions.reduceOrNull { all, next -> all and next } ?: Op.TRUE
    }

    /** An active color of the outer query's product with something left, but fewer than [threshold]. */
    private fun lowOnStock(threshold: Int): Op<Boolean> {
        val available = ProductVariantQmTbl.onHand - ProductVariantQmTbl.reserved
        return (ProductVariantQmTbl.productId eq ProductQmTbl.productId) and (ProductVariantQmTbl.isActive eq true) and
            (available greater intLiteral(0)) and (available less intLiteral(threshold))
    }

    /** The text in the search text, a code (the product's or a color's) that starts with it, or a color's exact barcode. */
    private fun matchesAdminSearch(q: String): Op<Boolean> {
        val code = q.uppercase()
        val colorMatches = ProductVariantQmTbl.select(ProductVariantQmTbl.id).where {
            (ProductVariantQmTbl.productId eq ProductQmTbl.productId) and
                ((ProductVariantQmTbl.sku.upperCase() like startingWith(code)) or (ProductVariantQmTbl.barcode eq q))
        }
        return (ProductQmTbl.searchText.lowerCase() like containing(q.lowercase())) or
            (ProductQmTbl.sku.upperCase() like startingWith(code)) or exists(colorMatches)
    }

    /** `word%`, with any `%` or `_` typed taken as themselves. */
    private fun startingWith(word: String): LikePattern = LikePattern.ofLiteral(word, ESCAPE) + "%"

    /** The first media of each product's default color, for those of [productIds] that have one. */
    private fun defaultMediaOf(productIds: List<Long>): Map<Long, GalleryItemDto> {
        if (productIds.isEmpty()) return emptyMap()
        return ProductVariantQmTbl.select(ProductVariantQmTbl.productId, ProductVariantQmTbl.media)
            .where { (ProductVariantQmTbl.productId inList productIds) and (ProductVariantQmTbl.isDefault eq true) }
            .mapNotNull { row -> row[ProductVariantQmTbl.media].firstOrNull()?.let { row[ProductVariantQmTbl.productId] to it.toDto() } }
            .toMap()
    }

    override fun findAdminDetail(productId: ProductId): AdminProductDetailQm? {
        val row = ProductQmTbl.selectAll().where { ProductQmTbl.productId eq productId.value }.singleOrNull() ?: return null
        val colors = ProductVariantQmTbl.selectAll()
            .where { ProductVariantQmTbl.productId eq productId.value }
            .orderBy(ProductVariantQmTbl.sortOrder to SortOrder.ASC, ProductVariantQmTbl.variantId to SortOrder.ASC)
            .toList()

        return AdminProductDetailQm(
            detail = detailOf(row, colors.filter { it[ProductVariantQmTbl.isActive] }),
            status = row[ProductQmTbl.status],
            version = row[ProductQmTbl.version],
            basePrice = priceTagOf(row[ProductQmTbl.basePrice], row[ProductQmTbl.baseCompareAtPrice]),
            seo = seoOf(row[ProductQmTbl.seo]),
            createdOn = row[ProductQmTbl.createdOn].toInstant(),
            createdBy = UserId.constructFromPersisted(row[ProductQmTbl.createdByUserId]),
            publishAt = row[ProductQmTbl.publishAt]?.toInstant(),
            sortPriority = row[ProductQmTbl.sortPriority],
            tags = row[ProductQmTbl.tags],
            variants = colors.map {
                AdminProductDetailQm.Variant(
                    variantId = VariantId.constructFromPersisted(it[ProductVariantQmTbl.variantId]),
                    colorKey = ColorKey.constructFromPersisted(it[ProductVariantQmTbl.colorKey]),
                    sku = Sku.constructFromPersisted(it[ProductVariantQmTbl.sku]),
                    barcode = it[ProductVariantQmTbl.barcode],
                    priceTag = priceTagOf(it[ProductVariantQmTbl.price], it[ProductVariantQmTbl.compareAtPrice]),
                    ownPrice = priceTagOf(it[ProductVariantQmTbl.price], it[ProductVariantQmTbl.compareAtPrice]).takeIf { _ -> it[ProductVariantQmTbl.hasOwnPrice] },
                    sortOrder = it[ProductVariantQmTbl.sortOrder],
                    isDefault = it[ProductVariantQmTbl.isDefault],
                    isActive = it[ProductVariantQmTbl.isActive],
                    onHand = it[ProductVariantQmTbl.onHand],
                    reserved = it[ProductVariantQmTbl.reserved],
                    soldCount = it[ProductVariantQmTbl.soldCount],
                    media = it[ProductVariantQmTbl.media].map { media -> media.toDto() },
                )
            },
        )
    }

    // ============ one color, slugs and codes in use ============

    override fun findVariants(variantIds: Collection<VariantId>): Map<VariantId, VariantQm> {
        if (variantIds.isEmpty()) return emptyMap()
        return ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.variantId inList variantIds.map { it.value } }.associate { row ->
            VariantId.constructFromPersisted(row[ProductVariantQmTbl.variantId]) to VariantQm(
                variantId = VariantId.constructFromPersisted(row[ProductVariantQmTbl.variantId]),
                productId = ProductId.constructFromPersisted(row[ProductVariantQmTbl.productId]),
                colorKey = ColorKey.constructFromPersisted(row[ProductVariantQmTbl.colorKey]),
                sku = Sku.constructFromPersisted(row[ProductVariantQmTbl.sku]),
                isActive = row[ProductVariantQmTbl.isActive],
            )
        }
    }

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

    override fun refreshSearchText(of: NamedInSearch): Int {
        val showing = when (of) {
            is NamedInSearch.Category -> ProductQmTbl.categorySlug eq of.slug.value
            is NamedInSearch.Material -> ProductQmTbl.materialSlug eq of.slug.value
            // Only active colors are in the search text, and colorKeys holds exactly those.
            is NamedInSearch.Color -> Overlaps(ProductQmTbl.colorKeys, listOf(of.key.value))
        }
        // In one order, so two of these at once take the products' locks the same way round.
        val productIds = ProductQmTbl.select(ProductQmTbl.productId).where { showing }
            .orderBy(ProductQmTbl.productId to SortOrder.ASC)
            .map { it[ProductQmTbl.productId] }
        productIds.forEach {
            ProductQmSummary.lock(it)
            ProductQmSummary.refresh(it)
        }
        return productIds.size
    }

    // productqm_publish_due_idx (V24) holds only scheduled rows.
    override fun findDueScheduled(now: Instant, limit: Int): List<ProductId> =
        ProductQmTbl.select(ProductQmTbl.productId)
            .where { (ProductQmTbl.status eq ProductStatus.DRAFT) and (ProductQmTbl.publishAt lessEq now.atOffset(ZoneOffset.UTC)) }
            .orderBy(ProductQmTbl.publishAt to SortOrder.ASC, ProductQmTbl.productId to SortOrder.ASC)
            .limit(limit)
            .map { ProductId.constructFromPersisted(it[ProductQmTbl.productId]) }

    override fun isSlugTaken(slug: Slug): Boolean =
        !ProductQmTbl.select(ProductQmTbl.id).where { ProductQmTbl.slug eq slug.value }.empty()

    // A code is unique across products and their colors: KV-1001 can't be one product's and another's color's.
    override fun findBriefs(ids: Collection<ProductId>): Map<ProductId, ProductBriefQm> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.map { it.value }
        val photos = ProductVariantQmTbl.select(ProductVariantQmTbl.productId, ProductVariantQmTbl.media)
            .where { (ProductVariantQmTbl.productId inList wanted) and (ProductVariantQmTbl.isDefault eq true) }
            .mapNotNull { row -> row[ProductVariantQmTbl.media].firstOrNull { !it.isVideo }?.let { row[ProductVariantQmTbl.productId] to it.toDto().media } }
            .toMap()
        return ProductQmTbl.select(ProductQmTbl.productId, ProductQmTbl.slug, ProductQmTbl.sku, ProductQmTbl.name, ProductQmTbl.status)
            .where { ProductQmTbl.productId inList wanted }
            .associate { row ->
                val id = row[ProductQmTbl.productId]
                ProductId.constructFromPersisted(id) to ProductBriefQm(
                    ProductId.constructFromPersisted(id), Slug.constructFromPersisted(row[ProductQmTbl.slug]), Sku.constructFromPersisted(row[ProductQmTbl.sku]),
                    ProductName.constructFromPersisted(row[ProductQmTbl.name]), row[ProductQmTbl.status], photos[id],
                )
            }
    }

    override fun findExistingIds(ids: Collection<ProductId>): Set<ProductId> {
        if (ids.isEmpty()) return emptySet()
        return ProductQmTbl.select(ProductQmTbl.productId).where { ProductQmTbl.productId inList ids.map { it.value } }
            .map { ProductId.constructFromPersisted(it[ProductQmTbl.productId]) }.toSet()
    }

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

        val ids = rows.map { it[ProductQmTbl.productId] }
        val colors = activeColorsOf(ids)
        val ratings = ratingsOf(ids)
        return rows.map { cardOf(it, colors[it[ProductQmTbl.productId]].orEmpty(), ratings[it[ProductQmTbl.productId]]) }
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

    override fun countAllByCategory(): Map<Slug, Int> =
        countAllBy(ProductQmTbl.categorySlug).mapKeys { Slug.constructFromPersisted(it.key) }

    override fun countAllByMaterial(): Map<Slug, Int> =
        countAllBy(ProductQmTbl.materialSlug).mapKeys { Slug.constructFromPersisted(it.key) }

    // A product has each color once (productvariantqm_tbl_product_color_key), so its colors count its products.
    override fun countAllWithColor(key: ColorKey): Int =
        ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.colorKey eq key.value }.count().toInt()

    /** How many products of any status have each value of [column]. */
    private fun <T> countAllBy(column: Column<T>): Map<T, Int> {
        val products = ProductQmTbl.id.count()
        return ProductQmTbl.select(column, products).groupBy(column).associate { it[column] to it[products].toInt() }
    }

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
            val own: List<Pair<Expression<*>, SortOrder>> = when (sort) {
                ProductSorts.NEWEST -> listOf(ProductQmTbl.firstPublishedOn to SortOrder.DESC)
                ProductSorts.BESTSELLING -> listOf(ProductQmTbl.soldCount to SortOrder.DESC)
                ProductSorts.PRICE_ASC -> listOf(ProductQmTbl.price to SortOrder.ASC)
                ProductSorts.PRICE_DESC -> listOf(ProductQmTbl.price to SortOrder.DESC)
                ProductSorts.DISCOUNT_DESC -> listOf(ProductQmTbl.discountPercent to SortOrder.DESC)
                // The rating is apart (productrating_tbl, phase 8): its average, then how many, then the newest; none last.
                ProductSorts.RATING_DESC -> listOf(
                    RatingOf("r.sum::numeric / NULLIF(r.count, 0)") to SortOrder.DESC_NULLS_LAST,
                    RatingOf("r.count") to SortOrder.DESC_NULLS_LAST,
                    ProductQmTbl.firstPublishedOn to SortOrder.DESC,
                )
                else -> error("No database order for sort '$sort'")
            }
            if (ProductSorts.putsSoldOutLast(sort)) listOf(ProductQmTbl.inStock to SortOrder.DESC) + own else own
        }

        /** What each of AdminProductSorts means here; the largest productId breaks ties. Nothing puts sold-out last. */
        val adminSortOrders: SortOrders = AdminProductSorts.names.associateWith { sort ->
            val own: Pair<Expression<*>, SortOrder> = when (sort) {
                AdminProductSorts.UPDATED -> ProductQmTbl.updatedOn to SortOrder.DESC
                AdminProductSorts.NEWEST -> ProductQmTbl.createdOn to SortOrder.DESC
                AdminProductSorts.NAME -> ProductQmTbl.name to SortOrder.ASC
                AdminProductSorts.PRICE_ASC -> ProductQmTbl.price to SortOrder.ASC
                AdminProductSorts.PRICE_DESC -> ProductQmTbl.price to SortOrder.DESC
                AdminProductSorts.BESTSELLING -> ProductQmTbl.soldCount to SortOrder.DESC
                AdminProductSorts.STOCK_ASC -> ProductQmTbl.availableTotal to SortOrder.ASC
                else -> error("No database order for sort '$sort'")
            }
            listOf(own)
        }
    }
}

/**
 * One number of the product's rating (`productrating_tbl`), as a subquery of the product's row: [sql] over the rating
 * row `r`. Null for a product without a rating row.
 */
private class RatingOf(private val sql: String) : Expression<Any>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("(SELECT $sql FROM productrating_tbl r WHERE r.\"productId\" = productqm_tbl.\"productId\")")
    }
}
