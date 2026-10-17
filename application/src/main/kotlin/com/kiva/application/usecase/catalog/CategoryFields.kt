package com.kiva.application.usecase.catalog

import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.required
import com.kiva.common.railway.zip
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryDescription
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.BagType

/**
 * The contract's CategoryUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and
 * `PUT /admin/categories*` (CreateCategoryUc, UpdateCategoryUc). [check] reads it the same way for both.
 */
data class CategoryFields(
    val name: String?,
    val slug: String?,
    val description: String?,
    val iconBagType: String?,
    val iconColorKey: String?,
    val imageMediaId: String?,
    val sortOrder: Int?,
    val showInMenu: Boolean?,
    val seo: Seo?,
) {
    /** The `seo` object: only these three fields are read; the others are the server's. */
    data class Seo(val title: String?, val description: String?, val noIndex: Boolean?)

    /** The form, checked: every value ready for the domain. Defaults are the contract's (sortOrder 0, showInMenu). */
    data class Checked(
        val slug: Slug,
        val name: CategoryName,
        val description: CategoryDescription?,
        val icon: CategoryIcon,
        val imageMediaId: MediaId?,
        val sortOrder: Int,
        val showInMenu: Boolean,
        val seo: SeoMeta,
    )

    /** Every problem of the form at once, each under its field (`name`, `iconColorKey`, `seo.title`). */
    fun check(): Result<Checked, InputProblems> = Result.zip(
        Result.zip(
            name.required().forField("name").bind { CategoryName.makeNew(it) },
            slug.required().forField("slug").bind { Slug.makeNew(it).forField("slug") },
            CategoryDescription.makeNew(description),
            ::InputProblems,
        ) { name, slug, description -> Triple(name, slug, description) },
        Result.zip(
            iconBagType.required().forField("iconBagType").bind { BagType.makeNew(it).forField("iconBagType") },
            iconColorKey.required().forField("iconColorKey").bind { ColorKey.makeNew(it).forField("iconColorKey") },
            ::InputProblems,
        ) { bagType, colorKey -> CategoryIcon(bagType, colorKey) },
        imageMediaId?.let { MediaId.makeNew(it).forField("imageMediaId") } ?: Result.success(null),
        SeoMeta.makeNew(seo?.title, seo?.description, seo?.noIndex),
        { problems: List<ResultFailure> -> InputProblems(problems.flattenGroups()) },
    ) { (name, slug, description), icon, imageMediaId, seo ->
        Checked(slug, name, description, icon, imageMediaId, sortOrder ?: 0, showInMenu ?: true, seo)
    }
}
