package com.kiva.application.util

import com.kiva.application.repository.AdminProductDetailQm
import com.kiva.application.repository.AdminProductFilter
import com.kiva.application.repository.AdminProductQm
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductDetailQm
import com.kiva.application.repository.ProductFacetsQm
import com.kiva.application.repository.ProductFilter
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.ProductRef
import com.kiva.application.repository.VariantQm
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.repository.MaterialRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

// Fakes of the read model and the catalog repositories: a test overrides only what its use case calls, and anything
// else fails loudly. A port that grows a method changes this file only.

private fun unused(): Nothing = error("not used here")

/** [ProductQmRepository] where every call fails until a test overrides it. */
abstract class UnusedProductQmRepository : ProductQmRepository {
    override fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm> = unused()
    override fun findDetail(ref: ProductRef): ProductDetailQm? = unused()
    override fun facets(filter: ProductFilter, now: Instant): ProductFacetsQm = unused()
    override fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>? = unused()
    override fun findForAdmin(filter: AdminProductFilter, pageable: Pageable): Page<AdminProductQm> = unused()
    override fun findAdminDetail(productId: ProductId): AdminProductDetailQm? = unused()
    override fun findVariant(variantId: VariantId): VariantQm? = unused()
    override fun isSlugTaken(slug: Slug): Boolean = unused()
    override fun findTakenSkus(skus: Collection<Sku>): Set<Sku> = unused()
    override fun countActiveByCategory(): Map<Slug, Int> = unused()
    override fun countActiveByMaterial(): Map<Slug, Int> = unused()
    override fun countActiveByColor(): Map<ColorKey, Int> = unused()
    override fun countAllByCategory(): Map<Slug, Int> = unused()
    override fun countAllByMaterial(): Map<Slug, Int> = unused()
    override fun countAllWithColor(key: ColorKey): Int = unused()
    override fun refreshSearchText(of: ProductQmRepository.NamedInSearch): Int = unused()
}

/** [CategoryRepository] where every call fails until a test overrides it. */
abstract class UnusedCategoryRepository : CategoryRepository {
    override fun findAll(): List<Category> = unused()
    override fun findBySlug(slug: Slug): Category? = unused()
    override fun findById(id: CategoryId): Category? = unused()
    override fun insert(category: Category): Unit = unused()
    override fun update(category: Category): Unit = unused()
    override fun delete(id: CategoryId): Unit = unused()
    override fun countWithIcon(colorKey: ColorKey): Int = unused()
}

/** [ColorRepository] where every call fails until a test overrides it. */
abstract class UnusedColorRepository : ColorRepository {
    override fun findAll(): List<Color> = unused()
    override fun findByKey(key: ColorKey): Color? = unused()
    override fun maxSortOrder(): Int? = unused()
    override fun insert(color: Color): Unit = unused()
    override fun update(color: Color): Unit = unused()
    override fun delete(key: ColorKey): Unit = unused()
}

/** [MaterialRepository] where every call fails until a test overrides it. */
abstract class UnusedMaterialRepository : MaterialRepository {
    override fun findAll(): List<Material> = unused()
    override fun findById(id: MaterialId): Material? = unused()
    override fun findBySlug(slug: Slug): Material? = unused()
    override fun insert(material: Material): Unit = unused()
    override fun update(material: Material): Unit = unused()
    override fun delete(id: MaterialId): Unit = unused()
}
