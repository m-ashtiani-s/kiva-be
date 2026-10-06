package com.kiva.application.util.factory

import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor

object ColorFactory {
    fun generate(
        key: String = "lilac",
        name: String = "یاسی",
        hex: String = "#C8B6E2",
        isLight: Boolean = true,
        sortOrder: Int = 4,
    ): Color = Color.constructFromPersisted(
        ColorKey.constructFromPersisted(key),
        ColorName.constructFromPersisted(name),
        HexColor.constructFromPersisted(hex),
        isLight,
        sortOrder,
    )
}
