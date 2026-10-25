package com.kiva.domain.aggregate.material.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.model.valueobject.MaterialNote

/**
 * What a bag is made of (چرم طبیعی گاوی، برزنت ضدآب، ...): the material filter and the product's spec table.
 * A product's own wording ("ساتن با سگک فلزی طلایی") is its materialDescription, not a material of its own.
 *
 * Reference data, stored in a plain table rather than as events: materials change rarely, have no lifecycle,
 * and nobody needs their history.
 *
 * Its identity is [id]; [slug] is unique too and is what the filter uses (`?material=genuine-cow-leather`). The slug
 * may change: the read model follows it (ON UPDATE CASCADE).
 *
 * Rules it keeps: none between its fields. It can't know whether its slug is free: the use case checks.
 */
class Material private constructor(
    val id: MaterialId,
    val slug: Slug,
    val name: MaterialName,
    val family: MaterialFamily,
    val description: MaterialNote?,
    val careInstructions: CareInstructions,
) {
    /** Every value of the panel's form, at once: `PUT` replaces them all. The same id, so the same material. */
    fun change(slug: Slug, name: MaterialName, family: MaterialFamily, description: MaterialNote?, careInstructions: CareInstructions): Material =
        Material(id, slug, name, family, description, careInstructions)

    /** Whether [other] holds exactly the same values (equality is by [id] only). */
    fun sameValuesAs(other: Material): Boolean =
        slug == other.slug && name == other.name && family == other.family && description == other.description &&
            careInstructions == other.careInstructions

    override fun equals(other: Any?): Boolean = other is Material && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Material(id=${id.value}, slug=${slug.value})"

    companion object {
        /** A new material from the panel, with an id taken from MaterialIdGenerator. */
        fun create(
            id: MaterialId,
            slug: Slug,
            name: MaterialName,
            family: MaterialFamily,
            description: MaterialNote?,
            careInstructions: CareInstructions,
        ): Material = Material(id, slug, name, family, description, careInstructions)

        fun constructFromPersisted(
            id: MaterialId,
            slug: Slug,
            name: MaterialName,
            family: MaterialFamily,
            description: MaterialNote?,
            careInstructions: CareInstructions,
        ): Material = Material(id, slug, name, family, description, careInstructions)
    }
}
