package com.kiva.common.railway

/**
 * Several input problems found together by one zip of a bigger form: a form with more inputs than one zip takes is
 * checked in groups, each group zipped into this.
 *
 * Only for collecting. The use case's own failure carries the problems flat ([flattenGroups]), so a client never sees
 * the grouping:
 * ```kotlin
 * Result.zip(
 *     Result.zip(name, slug, sku, ::InputProblems) { ... },
 *     Result.zip(price, compareAtPrice, ::InputProblems) { ... },
 *     { problems -> Failure.InvalidInput(problems.flattenGroups()) },
 * ) { ... }
 * ```
 */
class InputProblems(causes: List<ResultFailure>) : ResultFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = causes)

/** Every [InputProblems] replaced by what it holds, at any depth. A problem with a field of its own stays as it is. */
fun List<ResultFailure>.flattenGroups(): List<ResultFailure> =
    flatMap { if (it is InputProblems) it.causes.flattenGroups() else listOf(it) }
