package com.kiva.application.usecase.audit

import com.kiva.application.port.AuditChange
import com.kiva.common.valueobject.Money
import java.time.Instant

/**
 * Builds a change log row's `changes`: only the fields whose value differs, each written as text the same way
 * everywhere (roadmap section 5.1).
 *
 * | value | `from` / `to` |
 * |---|---|
 * | number, money | Latin digits, no separators (`"1890000"`) |
 * | boolean | `"true"` / `"false"` |
 * | enum | its name |
 * | instant | ISO-8601 in UTC |
 * | list, small object | compact JSON ([list], [json]); objects with sorted keys |
 * | short text (up to 200) | itself |
 * | long text, HTML ([longText]) and secrets ([hidden]) | both null: only the fact that it changed is kept |
 *
 * ```
 * val changes = AuditDiff.build {
 *     field("name", before.name.value, after.name.value)
 *     field("price", before.price, after.price)
 *     longText("description", before.description?.value, after.description?.value)
 * }
 * ```
 */
class AuditDiff private constructor() {

    private val changes = mutableListOf<AuditChange>()

    /** A plain value: a number, [Money], a boolean, an enum, an [Instant] or a short text. Longer text keeps only the fact. */
    fun field(name: String, before: Any?, after: Any?) {
        val from = text(before)
        val to = text(after)
        if (from == to) return
        if ((from?.length ?: 0) > MAX_SHORT_TEXT || (to?.length ?: 0) > MAX_SHORT_TEXT) changes += AuditChange(name, null, null)
        else changes += AuditChange(name, from, to)
    }

    /** A list, as compact JSON in the order given; null stays null. Sort it first when its order means nothing. */
    fun list(name: String, before: List<Any?>?, after: List<Any?>?) {
        field(name, before?.let(::jsonOf), after?.let(::jsonOf))
    }

    /** A small object, as compact JSON with its keys sorted, so the same object is always the same text. */
    fun json(name: String, before: Map<String, Any?>?, after: Map<String, Any?>?) {
        field(name, before?.let(::jsonOf), after?.let(::jsonOf))
    }

    /** Long text or HTML: only that it changed, never the text (a description, a page's content). */
    fun longText(name: String, before: String?, after: String?) {
        if (before != after) changes += AuditChange(name, null, null)
    }

    /** A value that never goes into the log (a bank reference, a phone, an email): only that it changed. */
    fun hidden(name: String, before: Any?, after: Any?) {
        if (text(before) != text(after)) changes += AuditChange(name, null, null)
    }

    companion object {
        /** The longest text kept as itself. */
        const val MAX_SHORT_TEXT = 200

        /** The fields [block] found changed, in the order it named them; empty when nothing changed. */
        fun build(block: AuditDiff.() -> Unit): List<AuditChange> = AuditDiff().apply(block).changes.toList()

        /** [value] as the log writes it. */
        fun text(value: Any?): String? = when (value) {
            null -> null
            is String -> value
            is Money -> value.toman.toString()
            is Number, is Boolean -> value.toString()
            is Enum<*> -> value.name
            is Instant -> value.toString()
            is List<*> -> jsonOf(value)
            is Map<*, *> -> jsonOf(value)
            else -> value.toString()
        }

        private fun jsonOf(value: Any?): String = when (value) {
            null -> "null"
            is String -> quoted(value)
            is Money -> value.toman.toString()
            is Number, is Boolean -> value.toString()
            is Enum<*> -> quoted(value.name)
            is Instant -> quoted(value.toString())
            is List<*> -> value.joinToString(",", "[", "]") { jsonOf(it) }
            is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
                .joinToString(",", "{", "}") { (key, item) -> "${quoted(key.toString())}:${jsonOf(item)}" }
            else -> quoted(value.toString())
        }

        private fun quoted(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
