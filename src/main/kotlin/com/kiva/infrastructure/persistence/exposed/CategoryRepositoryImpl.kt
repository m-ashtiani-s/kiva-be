package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.repository.StillReferencedException
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryDescription
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.infrastructure.common.persistence.isForeignKeyRefusal
import com.kiva.infrastructure.common.persistence.refusedConstraint
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

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

    override fun insert(category: Category) {
        writing(category) {
            CategoryTbl.insert {
                it[id] = category.id.value
                it.fill(category)
            }
        }
    }

    override fun update(category: Category) {
        writing(category) {
            CategoryTbl.update({ CategoryTbl.id eq category.id.value }) { it.fill(category) }
        }
    }

    override fun delete(id: CategoryId) {
        try {
            CategoryTbl.deleteWhere { CategoryTbl.id eq id.value }
        } catch (e: ExposedSQLException) {
            if (e.isForeignKeyRefusal()) throw StillReferencedException("category ${id.value}", e)
            throw e
        }
    }

    override fun countWithIcon(colorKey: ColorKey): Int =
        CategoryTbl.selectAll().where { CategoryTbl.iconColorKey eq colorKey.value }.count().toInt()

    /** The unique key on the slug is the last word on it; any other refusal is a bug and goes up as it is. */
    private fun writing(category: Category, write: () -> Unit) {
        try {
            write()
        } catch (e: ExposedSQLException) {
            if (e.refusedConstraint() == CategoryTbl.SLUG_KEY) throw UniqueTakenException("category slug ${category.slug.value}", e)
            throw e
        }
    }

    private fun UpdateBuilder<*>.fill(category: Category) {
        this[CategoryTbl.slug] = category.slug.value
        this[CategoryTbl.name] = category.name.value
        this[CategoryTbl.description] = category.description?.value
        this[CategoryTbl.iconBagType] = category.icon.bagType
        this[CategoryTbl.iconColorKey] = category.icon.colorKey.value
        this[CategoryTbl.sortOrder] = category.sortOrder
        this[CategoryTbl.showInMenu] = category.showInMenu
        this[CategoryTbl.imageMediaId] = category.imageMediaId?.value
        this[CategoryTbl.seoTitle] = category.seo.title
        this[CategoryTbl.seoDescription] = category.seo.description
        this[CategoryTbl.seoNoIndex] = category.seo.noIndex
    }

    private fun ResultRow.toCategory(): Category =
        Category.constructFromPersisted(
            id = CategoryId.constructFromPersisted(this[CategoryTbl.id].value),
            slug = Slug.constructFromPersisted(this[CategoryTbl.slug]),
            name = CategoryName.constructFromPersisted(this[CategoryTbl.name]),
            description = this[CategoryTbl.description]?.let(CategoryDescription::constructFromPersisted),
            icon = CategoryIcon(
                bagType = this[CategoryTbl.iconBagType],
                colorKey = ColorKey.constructFromPersisted(this[CategoryTbl.iconColorKey]),
            ),
            sortOrder = this[CategoryTbl.sortOrder],
            showInMenu = this[CategoryTbl.showInMenu],
            imageMediaId = this[CategoryTbl.imageMediaId]?.let(MediaId::constructFromPersisted),
            seo = SeoMeta.constructFromPersisted(this[CategoryTbl.seoTitle], this[CategoryTbl.seoDescription], this[CategoryTbl.seoNoIndex]),
        )
}

