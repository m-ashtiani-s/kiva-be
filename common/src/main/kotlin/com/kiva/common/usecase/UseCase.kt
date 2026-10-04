package com.kiva.common.usecase

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * A use case that CHANGES something: places an order, registers a customer, deactivates a product.
 *
 * Unlike the company version, a command may return a value ([R]), but ONLY an identifier of what it
 * created (the client needs the new order number) or `Unit`. Never a DTO or any other read data:
 * building views is the job of a [QueryUseCase], so the write side and the read side stay separate (CQRS).
 * If the client needs details after a command, it calls a query with the returned id.
 *
 * `suspend` because a use case talks to the database and other services; it must not block a thread while waiting.
 */
interface CommandUseCase<in C, out R, out F : ResultFailure> {
    suspend fun execute(command: C): Result<R, F>
}

/**
 * A use case that only READS: product details, my orders, product list.
 * It must not change anything, so it is always safe to call twice.
 */
interface QueryUseCase<in Q, out R, out F : ResultFailure> {
    suspend fun execute(query: Q): Result<R, F>
}
