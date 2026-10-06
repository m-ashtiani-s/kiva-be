package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Runs inside the use case's transaction; it never opens one itself. */
class CategoryRepositoryImpl : CategoryRepository {

    // pId breaks ties, so two categories with the same sortOrder still come back in a stable order.
    override fun findAll(): List<Category> =
        CategoryTbl.selectAll()
            .orderBy(CategoryTbl.sortOrder to SortOrder.ASC, CategoryTbl.id to SortOrder.ASC)
            .map { it.toCategory() }

    override fun findBySlug(slug: Slug): Category? =
        CategoryTbl.selectAll()
            .where { CategoryTbl.slug eq slug.value }
            .singleOrNull()
            ?.toCategory()

    override fun findById(id: CategoryId): Category? =
        CategoryTbl.selectAll()
            .where { CategoryTbl.id eq id.value }
            .singleOrNull()
            ?.toCategory()

    private fun ResultRow.toCategory(): Category =
        Category.constructFromPersisted(
            id = CategoryId.constructFromPersisted(this[CategoryTbl.id].value),
            slug = Slug.constructFromPersisted(this[CategoryTbl.slug]),
            name = CategoryName.constructFromPersisted(this[CategoryTbl.name]),
            description = this[CategoryTbl.description],
            icon = CategoryIcon(
                bagType = this[CategoryTbl.iconBagType],
                colorKey = ColorKey.constructFromPersisted(this[CategoryTbl.iconColorKey]),
            ),
            sortOrder = this[CategoryTbl.sortOrder],
            showInMenu = this[CategoryTbl.showInMenu],
        )
}
