package com.kiva.application.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductRefTest {

    @ParameterizedTest
    @CsvSource("1, 1", "12, 12", "۱۲, 12", "' 7 ', 7")
    fun `digits only is a product's number, Persian digits too`(raw: String, id: Long) {
        assertEquals(ProductRef.ById(ProductId.constructFromPersisted(id)), ProductRef.parse(raw))
    }

    @ParameterizedTest
    @CsvSource("KV-1001, KV-1001", "KV-1001-LILAC, KV-1001-LILAC", "Kv-mahak, KV-MAHAK")
    fun `a capital letter makes it a code, read as staff type codes`(raw: String, sku: String) {
        assertEquals(ProductRef.BySku(Sku.constructFromPersisted(sku)), ProductRef.parse(raw))
    }

    // A slug is lowercase with at least one letter, so it is never a number or a code.
    @ParameterizedTest
    @ValueSource(strings = ["mahak-shoulder-bag", "kv-1001", "12abc"])
    fun `anything else is a slug`(raw: String) {
        assertEquals(ProductRef.BySlug(Slug.constructFromPersisted(raw)), ProductRef.parse(raw))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "0", "Mahak Bag", "mahak_bag", "-mahak"])
    fun `what can be none of the three names no product`(raw: String) {
        assertNull(ProductRef.parse(raw))
    }

    @Test
    fun `a number too big for an id names no product`() {
        assertNull(ProductRef.parse("99999999999999999999"))
    }
}
