package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkuTest {

    @Nested
    inner class TypedByStaff {
        @Test
        fun `uppercase letters, digits and single dashes are accepted, trimmed and uppercased`() {
            assertEquals("KV-1001", Sku.makeNew("KV-1001").getOrNull()?.value)
            assertEquals("KV-1001", Sku.makeNew("  kv-1001 ").getOrNull()?.value)
            assertEquals("MAHAK2", Sku.makeNew("mahak2").getOrNull()?.value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "   ", "1001", "KV--1001", "-KV", "KV-", "KV 1001", "KV_1001", "کیوا"])
        fun `anything else is SKU_INVALID on the sku field`(raw: String) {
            assertEquals(Result.failure(Sku.Failure.Invalid), Sku.makeNew(raw))
            assertEquals("sku", Sku.Failure.Invalid.field)
        }

        @Test
        fun `a typed code is at most 40 characters`() {
            assertEquals(40, Sku.makeNew("K".repeat(40)).getOrNull()?.value?.length)
            assertEquals(Result.failure(Sku.Failure.Invalid), Sku.makeNew("K".repeat(41)))
        }
    }

    @Nested
    inner class MadeFromTheId {
        private val lilac = ColorKey.constructFromPersisted("lilac")

        @Test
        fun `a product's code is KV- and 1000 plus its id, as in the contract's example`() {
            assertEquals("KV-1001", Sku.forProduct(ProductId.constructFromPersisted(1)).value)
            assertEquals("KV-1016", Sku.forProduct(ProductId.constructFromPersisted(16)).value)
        }

        @Test
        fun `a color's code is its product's code plus the whole color key in capitals`() {
            val mahak = Sku.constructFromPersisted("KV-1001")

            assertEquals("KV-1001-LILAC", Sku.forVariant(mahak, lilac).value)
            assertEquals("KV-1001-DARK-GREEN", Sku.forVariant(mahak, ColorKey.constructFromPersisted("dark-green")).value)
        }

        @Test
        fun `the longest typed code plus the longest color key still fits`() {
            val longest = Sku.makeNew("K".repeat(Sku.MAX_TYPED_LENGTH)).getOrNull()!!
            val longestKey = ColorKey.constructFromPersisted("k".repeat(31))

            assertEquals(72, Sku.forVariant(longest, longestKey).value.length)
        }
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertEquals("KV-1001", Sku.constructFromPersisted("KV-1001").value)
        assertFailsWith<IllegalArgumentException> { Sku.constructFromPersisted("kv-1001") }
        assertFailsWith<IllegalArgumentException> { Sku.constructFromPersisted("") }
    }
}
