package com.kiva.domain.aggregate.color.entity

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ColorTest {

    private fun color(key: String, name: String = "یاسی", hex: String = "#C8B6E2") =
        Color.constructFromPersisted(
            ColorKey.constructFromPersisted(key),
            ColorName.constructFromPersisted(name),
            HexColor.constructFromPersisted(hex),
            isLight = true,
            sortOrder = 4,
        )

    @Test
    fun `two colors with the same key are the same color, even if a name or swatch was edited`() {
        val before = color("lilac")
        val after = color("lilac", name = "یاسی روشن", hex = "#D8C6F2")

        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun `colors with different keys are different colors, even with the same name`() {
        assertNotEquals(color("lilac"), color("lavender"))
    }

    @Test
    fun `constructFromPersisted keeps every value`() {
        val lilac = color("lilac")

        assertEquals("lilac", lilac.key.value)
        assertEquals("یاسی", lilac.name.value)
        assertEquals("#C8B6E2", lilac.hex.value)
        assertEquals(true, lilac.isLight)
        assertEquals(4, lilac.sortOrder)
    }
}
