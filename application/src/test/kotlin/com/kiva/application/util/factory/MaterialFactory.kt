package com.kiva.application.util.factory

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.model.valueobject.MaterialNote

object MaterialFactory {
    fun generate(
        id: Long = 1,
        slug: String = "genuine-cow-leather",
        name: String = "چرم طبیعی گاوی",
        family: MaterialFamily = MaterialFamily.GENUINE_LEATHER,
        description: String? = null,
        careInstructions: List<String> = emptyList(),
    ): Material = Material.constructFromPersisted(
        id = MaterialId.constructFromPersisted(id),
        slug = Slug.constructFromPersisted(slug),
        name = MaterialName.constructFromPersisted(name),
        family = family,
        description = description?.let(MaterialNote::constructFromPersisted),
        careInstructions = CareInstructions.constructFromPersisted(careInstructions),
    )
}
