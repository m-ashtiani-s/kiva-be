package com.kiva.common.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A logger named after the class it is used in:
 * ```kotlin
 * class PlaceOrderUc {
 *     private val logger = getLogger()   // logger name: com.kiva...PlaceOrderUc
 * }
 * ```
 * Inside a `companion object` it uses the outer class, not `...PlaceOrderUc$Companion`.
 * No class name to type, so nothing to get wrong when a file is copied.
 */
fun Any.getLogger(): Logger {
    val type = javaClass
    val owner = if (type.simpleName == "Companion" && type.enclosingClass != null) type.enclosingClass else type
    return LoggerFactory.getLogger(owner)
}
