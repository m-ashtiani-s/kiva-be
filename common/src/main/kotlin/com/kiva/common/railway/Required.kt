package com.kiva.common.railway

/**
 * A field the form must have was left out or sent as null (`FIELD_REQUIRED`). No field here: the caller names it with
 * [forField], as for every shared failure.
 */
data object FieldRequired : ResultFailure("FIELD_REQUIRED", FailureKind.INVALID)

/**
 * The value, or [FieldRequired] when it didn't come. Request bodies have every field nullable, so a missing one is
 * reported by the use case together with every other problem of the form:
 * ```kotlin
 * command.iconBagType.required().bind { BagType.makeNew(it) }.forField("iconBagType")
 * ```
 */
fun <T : Any> T?.required(): Result<T, ResultFailure> = if (this != null) Result.success(this) else Result.failure(FieldRequired)
