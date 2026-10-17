package com.kiva.domain.aggregate.color.entity

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    @Test
    fun `create keeps every value - change keeps the key and replaces the rest`() {
        val created = Color.create(ColorKey.constructFromPersisted("mint"), ColorName.constructFromPersisted("نعنایی"), HexColor.constructFromPersisted("#A8E6CF"), true, 14)

        val changed = created.change(ColorName.constructFromPersisted("سبز نعنایی"), HexColor.constructFromPersisted("#98D6BF"), false, 2)

        assertEquals("mint", changed.key.value)
        assertEquals("سبز نعنایی", changed.name.value)
        assertEquals("#98D6BF", changed.hex.value)
        assertEquals(false, changed.isLight)
        assertEquals(2, changed.sortOrder)
        assertFalse(changed.sameValuesAs(created))
        assertTrue(created.change(created.name, created.hex, created.isLight, created.sortOrder).sameValuesAs(created))
    }
}
