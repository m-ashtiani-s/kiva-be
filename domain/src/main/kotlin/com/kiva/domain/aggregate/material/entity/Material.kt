package com.kiva.domain.aggregate.material.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName

/**
 * What a bag is made of (چرم طبیعی گاوی، برزنت ضدآب، ...): the material filter and the product's spec table.
 * A product's own wording ("ساتن با سگک فلزی طلایی") is its materialDescription, not a material of its own.
 *
 * Reference data, stored in a plain table rather than as events: materials change rarely, have no lifecycle,
 * and nobody needs their history.
 *
 * Its identity is [id]; [slug] is unique too and is what the filter uses (`?material=genuine-cow-leather`).
 */
class Material private constructor(
    val id: MaterialId,
    val slug: Slug,
    val name: MaterialName,
    val family: MaterialFamily,
    val description: String?,
    val careInstructions: List<String>,
) {
    override fun equals(other: Any?): Boolean = other is Material && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Material(id=${id.value}, slug=${slug.value})"

    companion object {
        fun constructFromPersisted(
            id: MaterialId,
            slug: Slug,
            name: MaterialName,
            family: MaterialFamily,
            description: String?,
            careInstructions: List<String>,
        ): Material = Material(id, slug, name, family, description, careInstructions.toList())
    }
}
