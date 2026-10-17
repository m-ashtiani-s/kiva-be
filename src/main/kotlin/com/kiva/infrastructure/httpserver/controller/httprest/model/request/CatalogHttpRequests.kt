package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.catalog.CategoryFields
import com.kiva.application.usecase.catalog.ColorFields
import com.kiva.application.usecase.catalog.MaterialFields
import kotlinx.serialization.Serializable

/**
 * The body of `POST` and `PUT /admin/categories*` (contract CategoryUpsertRequest). Every field nullable, so one left
 * out is reported by the use case together with every other problem of the form.
 */
@Serializable
data class CategoryUpsertHttpRequest(
    val name: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val iconBagType: String? = null,
    val iconColorKey: String? = null,
    val imageMediaId: String? = null,
    val sortOrder: Int? = null,
    val showInMenu: Boolean? = null,
    val seo: SeoHttpRequest? = null,
) {
    fun toFields() = CategoryFields(
        name, slug, description, iconBagType, iconColorKey, imageMediaId, sortOrder, showInMenu,
        seo?.let { CategoryFields.Seo(it.title, it.description, it.noIndex) },
    )
}

/** The `seo` object of a panel form: only these three fields are read; `canonicalUrl`, `ogImageUrl` and `jsonLd` are the server's. */
@Serializable
data class SeoHttpRequest(val title: String? = null, val description: String? = null, val noIndex: Boolean? = null)

/** The body of `POST` and `PUT /admin/colors*` (contract ColorUpsertRequest). */
@Serializable
data class ColorUpsertHttpRequest(
    val key: String? = null,
    val name: String? = null,
    val hex: String? = null,
    val isLight: Boolean? = null,
    val sortOrder: Int? = null,
) {
    fun toFields() = ColorFields(key, name, hex, isLight, sortOrder)
}

/** The body of `POST` and `PUT /admin/materials*` (contract MaterialUpsertRequest). */
@Serializable
data class MaterialUpsertHttpRequest(
    val name: String? = null,
    val slug: String? = null,
    val family: String? = null,
    val description: String? = null,
    val careInstructions: List<String>? = null,
) {
    fun toFields() = MaterialFields(name, slug, family, description, careInstructions)
}
