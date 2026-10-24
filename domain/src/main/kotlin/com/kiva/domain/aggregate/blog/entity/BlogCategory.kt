package com.kiva.domain.aggregate.blog.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId

/** A category of the blog («راهنمای خرید»), read only: made and changed with SQL (roadmap phase 15, decision 1). */
class BlogCategory private constructor(val id: BlogCategoryId, val slug: Slug, val name: String, val sortOrder: Int) {
    init {
        require(name.isNotBlank()) { "Blog category ${id.value} has no name" }
    }

    override fun equals(other: Any?): Boolean = other is BlogCategory && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "BlogCategory(id=${id.value}, slug=${slug.value})"

    companion object {
        fun constructFromPersisted(id: BlogCategoryId, slug: Slug, name: String, sortOrder: Int): BlogCategory = BlogCategory(id, slug, name, sortOrder)
    }
}
