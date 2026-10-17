package com.kiva.application.usecase.catalog

import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.required
import com.kiva.common.railway.zip
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.model.valueobject.MaterialNote

/**
 * The contract's MaterialUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and
 * `PUT /admin/materials*` (CreateMaterialUc, UpdateMaterialUc).
 */
data class MaterialFields(
    val name: String?,
    val slug: String?,
    val family: String?,
    val description: String?,
    val careInstructions: List<String>?,
) {
    data class Checked(
        val slug: Slug,
        val name: MaterialName,
        val family: MaterialFamily,
        val description: MaterialNote?,
        val careInstructions: CareInstructions,
    )

    /** Every problem of the form at once, each under its field. */
    fun check(): Result<Checked, InputProblems> = Result.zip(
        Result.zip(
            name.required().forField("name").bind { MaterialName.makeNew(it) },
            slug.required().forField("slug").bind { Slug.makeNew(it).forField("slug") },
            family.required().forField("family").bind { MaterialFamily.makeNew(it) },
            ::InputProblems,
        ) { name, slug, family -> Triple(name, slug, family) },
        MaterialNote.makeNew(description),
        CareInstructions.makeNew(careInstructions),
        { problems: List<ResultFailure> -> InputProblems(problems.flattenGroups()) },
    ) { (name, slug, family), description, care -> Checked(slug, name, family, description, care) }
}
