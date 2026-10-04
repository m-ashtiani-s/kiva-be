package com.kiva.common.logging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LoggingTest {

    private class OrderService {
        val logger = getLogger()

        companion object {
            val companionLogger = getLogger()
        }
    }

    @Test
    fun `logger is named after the class it is used in`() {
        assertEquals(OrderService::class.java.name, OrderService().logger.name)
    }

    @Test
    fun `inside a companion object it uses the outer class`() {
        assertEquals(OrderService::class.java.name, OrderService.companionLogger.name)
    }
}
