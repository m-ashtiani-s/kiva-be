package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.IdempotencyKey
import com.kiva.application.port.IdempotencyScope
import com.kiva.application.port.IdempotencyStore
import com.kiva.application.port.IdempotentRequest
import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.idempotency.IdempotencyGuard
import com.kiva.application.usecase.idempotency.IdempotencyGuard.Run
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.persistence.exposed.tbl.IdempotencyKeyTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Idempotency keys on kiva_test (roadmap section 12), through IdempotencyGuard and the real store, each request in its
 * own transaction as a use case runs it. The work here is a stand-in for placing an order: it counts how often it ran
 * and «makes» an order code. Its customers are real accounts (a key belongs to one); they and their keys are deleted
 * after each test.
 */
class IdempotencyGuardOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2036-09-01T09:00:00Z"))
    private val transactions = ExposedTransactionManager(db)
    private val store = IdempotencyStoreImpl()
    private val guard = IdempotencyGuard(store, clock)

    private val sara = customer("09908000001")
    private val mina = customer("09908000002")

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) { UserTbl.deleteWhere { phone like "0990800%" } } // their keys go with them
    }

    private fun customer(phone: String): UserId = transaction(db) {
        UserRepositoryImpl().findOrRegister(IranianMobileNumber.constructFromPersisted(phone), clock.now).user.id
    }

    private fun request(user: UserId = sara, key: UUID, body: String = """{"body":{"shippingMethod":"POST"},"path":{}}""") =
        IdempotentRequest(user, IdempotencyScope.PLACE_ORDER, IdempotencyKey.makeNew(key.toString()).getOrNull()!!, RequestHash.of(body))

    private object CartEmpty : ResultFailure("CART_EMPTY", FailureKind.BUSINESS_RULE)

    private val worked = AtomicInteger()

    /** One request through the guard, as a use case runs it: its own transaction, rolled back on a failure. */
    private suspend fun place(request: IdempotentRequest, fails: Boolean = false, holdFor: Long = 0): Result<Run<String>, ResultFailure> =
        transactions.inTransaction {
            guard.once(request, outcomeOf = { code: String -> mapOf("orderCode" to code) }) {
                val n = worked.incrementAndGet()
                Thread.sleep(holdFor)
                if (fails) Result.failure(CartEmpty) else Result.success("KV-${218_340 + n}")
            }
        }

    private fun rowsOf(user: UserId): Int = transaction(db) {
        IdempotencyKeyTbl.selectAll().where { IdempotencyKeyTbl.userId eq user.value }.count().toInt()
    }

    @Test
    fun `a new key - the work is done and what it made is recorded`() = runBlocking<Unit> {
        val key = UUID.randomUUID()

        val first = place(request(key = key)).getOrNull()

        assertEquals(Run.Done("KV-218341"), first)
        assertEquals(1, worked.get())
        val row = transaction(db) { IdempotencyKeyTbl.selectAll().where { (IdempotencyKeyTbl.userId eq sara.value) and (IdempotencyKeyTbl.key eq key) }.single() }
        assertEquals(mapOf("orderCode" to "KV-218341"), row[IdempotencyKeyTbl.outcome])
        assertEquals(IdempotencyScope.PLACE_ORDER, row[IdempotencyKeyTbl.scope])
        assertEquals(clock.now, row[IdempotencyKeyTbl.createdOn].toInstant())
    }

    @Test
    fun `the same key and the same request again - the same outcome, and the work is not done again`() = runBlocking<Unit> {
        val key = UUID.randomUUID()
        place(request(key = key))

        val again = place(request(key = key)).getOrNull()

        assertEquals(Run.Replayed(mapOf("orderCode" to "KV-218341")), again)
        assertEquals(1, worked.get())
        assertEquals(1, rowsOf(sara))
    }

    @Test
    fun `the same key with another request is a reused key, and nothing is done`() = runBlocking<Unit> {
        val key = UUID.randomUUID()
        place(request(key = key))

        val other = place(request(key = key, body = """{"body":{"shippingMethod":"TIPAX"},"path":{}}""")).getOrNull()

        assertEquals(Run.KeyReused, other)
        assertEquals(1, worked.get())
    }

    @Test
    fun `a key is its customer's - another customer's same key is a new key`() = runBlocking<Unit> {
        val key = UUID.randomUUID()
        place(request(user = sara, key = key))

        val minas = place(request(user = mina, key = key, body = """{"body":{"shippingMethod":"TIPAX"},"path":{}}""")).getOrNull()

        assertEquals(Run.Done("KV-218342"), minas)
        assertEquals(listOf(1, 1), listOf(rowsOf(sara), rowsOf(mina)))
    }

    @Test
    fun `the same key in another scope is another key`() = runBlocking<Unit> {
        val key = UUID.randomUUID()
        place(request(key = key))

        val retry = place(request(key = key).copy(scope = IdempotencyScope.RETRY_PAYMENT)).getOrNull()

        assertIs<Run.Done<String>>(retry)
    }

    @Test
    fun `a failure that rolls back leaves no record - its repeat runs again`() = runBlocking<Unit> {
        val key = UUID.randomUUID()

        val failed = place(request(key = key), fails = true)
        assertEquals(CartEmpty, failed.failureOrNull())
        assertEquals(0, rowsOf(sara))

        val fixed = place(request(key = key)).getOrNull()
        assertEquals(Run.Done("KV-218342"), fixed)
        assertEquals(2, worked.get())
    }

    @Test
    fun `ten requests with one key at the same moment - the work is done once and all ten get its outcome`() {
        val key = UUID.randomUUID()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)

        val results = try {
            // Each holds the key a moment while it works, so without the lock the requests would surely overlap.
            val calls = List(10) { pool.submit<Result<Run<String>, ResultFailure>> { start.await(); runBlocking { place(request(key = key), holdFor = 50) } } }
            start.countDown()
            calls.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, worked.get())
        val outcomes = results.map { result ->
            when (val run = result.getOrNull()) {
                is Run.Done -> mapOf("orderCode" to run.value)
                is Run.Replayed -> run.outcome
                else -> error("unexpected $result")
            }
        }
        assertEquals(List(10) { mapOf("orderCode" to "KV-218341") }, outcomes)
        assertEquals(1, results.count { it.getOrNull() is Run.Done })
        assertEquals(1, rowsOf(sara))
    }

    @Test
    fun `the store says the same for a repeat read inside one transaction`() = runBlocking<Unit> {
        val key = UUID.randomUUID()
        place(request(key = key))

        val earlier = transaction(db) { store.lock(request(key = key)) }

        assertTrue(earlier is IdempotencyStore.Earlier.SameRequest)
    }
}
