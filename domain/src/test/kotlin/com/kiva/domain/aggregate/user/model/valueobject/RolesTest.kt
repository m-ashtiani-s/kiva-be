package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.domain.aggregate.user.model.valueobject.Role.ADMIN
import com.kiva.domain.aggregate.user.model.valueobject.Role.CATALOG_MANAGER
import com.kiva.domain.aggregate.user.model.valueobject.Role.CUSTOMER
import com.kiva.domain.aggregate.user.model.valueobject.Role.OPERATOR
import com.kiva.domain.aggregate.user.model.valueobject.Role.SUPPORT
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RolesTest {

    @Test
    fun `CUSTOMER is always there, sent or not`() {
        assertEquals(listOf(CUSTOMER, OPERATOR), Roles.of(listOf(OPERATOR)).values.toList())
        assertEquals(listOf(CUSTOMER), Roles.of(emptyList()).values.toList())
        assertEquals(listOf(CUSTOMER), Roles.CUSTOMER_ONLY.values.toList())
    }

    @Test
    fun `roles are kept in the contract's order and once each, whatever order they came in`() {
        assertEquals(listOf(CUSTOMER, ADMIN, SUPPORT), Roles.of(listOf(SUPPORT, ADMIN, SUPPORT, CUSTOMER)).values.toList())
        assertEquals(Roles.of(listOf(SUPPORT, ADMIN)), Roles.of(listOf(ADMIN, SUPPORT)))
    }

    @Test
    fun `any role besides CUSTOMER makes staff`() {
        assertFalse(Roles.CUSTOMER_ONLY.isStaff)
        assertTrue(Roles.of(listOf(CATALOG_MANAGER)).isStaff)
        assertTrue(CATALOG_MANAGER in Roles.of(listOf(CATALOG_MANAGER)))
        assertFalse(ADMIN in Roles.of(listOf(CATALOG_MANAGER)))
    }

    @Test
    fun `stored roles are read back as they are`() {
        assertEquals(Roles.of(listOf(OPERATOR)), Roles.constructFromPersisted(listOf(OPERATOR, CUSTOMER)))
    }

    @Test
    fun `stored roles without CUSTOMER are a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { Roles.constructFromPersisted(listOf(ADMIN)) }
    }
}
