package com.kiva.common.railway

/**
 * Collects the problems of a form checked part by part, for a form too big for nested zips. Each part is asked once
 * ([take]); when none failed ([any] is false), every [Part.value] is there.
 * ```kotlin
 * val problems = Problems()
 * val name = problems.take(name.required().bind { ShippingMethodName.makeNew(it) }.forField("name"))
 * val days = problems.take(DeliveryDays.makeNew(minDays, maxDays))
 * if (problems.any()) return Result.failure(problems.all())
 * Draft(name.value, days.value)
 * ```
 */
class Problems {
    private val found = mutableListOf<ResultFailure>()

    class Part<T>(private val result: Result<T, ResultFailure>) {
        val value: T get() = (result as Result.Success).value
    }

    fun <T> take(result: Result<T, ResultFailure>): Part<T> {
        result.failureOrNull()?.let { found += it }
        return Part(result)
    }

    fun add(problem: ResultFailure) {
        found += problem
    }

    fun any(): Boolean = found.isNotEmpty()

    fun all(): InputProblems = InputProblems(found.toList())
}

/**
 * This failure one level down, under [field]: its own field (if any) stays as the next step of the path, so
 * `TEXT_TOO_LONG` on `name` under `1` under `shippingMethods` is `shippingMethods.1.name`. ([forField] instead replaces
 * the field, for a shared failure that doesn't know its own.)
 */
fun ResultFailure.under(field: String): FieldFailure = FieldFailure(field, InputProblems(listOf(this)))

/** Every one of these failures one level down, under [field]; see [under]. */
fun List<ResultFailure>.under(field: String): FieldFailure = FieldFailure(field, InputProblems(this))
