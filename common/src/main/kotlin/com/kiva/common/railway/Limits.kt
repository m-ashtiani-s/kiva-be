package com.kiva.common.railway

/**
 * The shared answers of a form's limits (roadmap phase 4, decision 21): a number outside its range, a text or a list
 * too long, a list with an item twice. No field here: the caller names it with [forField], as for every shared failure.
 * The client fills its sentence from `meta` («این عدد باید بین ۱ و ۱۴ باشه»).
 */
data class NumberOutOfRange(val min: Long, val max: Long) :
    ResultFailure("NUMBER_OUT_OF_RANGE", FailureKind.INVALID, meta = mapOf("min" to min, "max" to max))

data class TextTooLong(val maxLength: Int) : ResultFailure("TEXT_TOO_LONG", FailureKind.INVALID, meta = mapOf("maxLength" to maxLength))

data class TooManyItems(val maxItems: Int) : ResultFailure("TOO_MANY_ITEMS", FailureKind.INVALID, meta = mapOf("maxItems" to maxItems))

data object ListItemDuplicate : ResultFailure("LIST_ITEM_DUPLICATE", FailureKind.INVALID)

/** The number, when it came ([FieldRequired] when it didn't) and is in [range] ([NumberOutOfRange] when it isn't). */
fun Int?.requiredIn(range: IntRange): Result<Int, ResultFailure> = when {
    this == null -> Result.failure(FieldRequired)
    this !in range -> Result.failure(NumberOutOfRange(range.first.toLong(), range.last.toLong()))
    else -> Result.success(this)
}

/** The number, when it came ([FieldRequired] when it didn't) and is in [range] ([NumberOutOfRange] when it isn't). */
fun Long?.requiredIn(range: LongRange): Result<Long, ResultFailure> = when {
    this == null -> Result.failure(FieldRequired)
    this !in range -> Result.failure(NumberOutOfRange(range.first, range.last))
    else -> Result.success(this)
}
