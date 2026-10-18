package com.kiva.infrastructure.httpserver

import java.io.File

/**
 * The property names of the contract's schemas (api/kiva-openapi.yml), read by indentation, no YAML library: enough to
 * check a response has exactly the fields its schema has. A schema made with `allOf` has the properties of each part
 * (its `$ref`s followed). Only a schema's own first level: the properties of a nested inline object are not its own.
 */
object ContractSchemas {
    private val lines: List<String> by lazy { File("api/kiva-openapi.yml").readLines() }

    private val schemasStart: Int by lazy { lines.indexOfFirst { it == "  schemas:" } }

    fun propertiesOf(schema: String): Set<String> {
        val start = (schemasStart until lines.size).firstOrNull { lines[it] == "    $schema:" } ?: error("No schema $schema in the contract")
        val block = lines.drop(start + 1).takeWhile { it.isBlank() || indentOf(it) > 4 }
        val names = mutableSetOf<String>()
        // The schema's own `properties:`: at its top (6), or in each part of its allOf (10).
        val ownIndent = if (block.any { it == "      allOf:" }) 10 else 6
        var propertyIndent = -1
        block.forEach { line ->
            if (line.isBlank()) return@forEach
            val indent = indentOf(line)
            val text = line.trim()
            when {
                text == "properties:" && indent == ownIndent -> propertyIndent = indent + 2
                indent == propertyIndent && PROPERTY.matches(text) -> names += text.substringBefore(':')
                indent < propertyIndent -> propertyIndent = -1
            }
            REF_PART.find(line)?.let { names += propertiesOf(it.groupValues[1]) }
        }
        return names
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart().length

    private val PROPERTY = Regex("^[A-Za-z][A-Za-z0-9]*:.*")

    // An allOf part that is a whole schema: `        - $ref: '#/components/schemas/OrderSummary'`.
    private val REF_PART = Regex("^ {8}- \\\$ref: '#/components/schemas/([A-Za-z0-9]+)'")
}
