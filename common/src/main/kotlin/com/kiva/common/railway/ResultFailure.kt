package com.kiva.common.railway

/**
 * Base type of every failure a [Result] can carry.
 * Having one common parent lets outer layers (e.g. HTTP) handle any failure without knowing its concrete type.
 *
 * Kept empty for now; the failure details (code, field, causes) are designed in session 3.
 */
abstract class ResultFailure
