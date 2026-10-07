package com.kiva.infrastructure.common.persistence

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.TestDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs against the real `kiva_test` Postgres database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedTransactionManagerTest {

    // A throwaway table that exists only while this test class runs.
    private object Notes : Table("tx_test_notes") {
        val text = varchar("text", 50)
    }

    private data object Rejected : ResultFailure("REJECTED", FailureKind.CONFLICT)

    private val db = TestDatabase.database
    private val tx = ExposedTransactionManager(db)

    private fun write(text: String) {
        Notes.insert { it[Notes.text] = text }
    }

    /** Reads in a fresh transaction, so it only sees what was really committed. */
    private fun committedNotes(): List<String> = transaction(db) { Notes.selectAll().map { it[Notes.text] }.sorted() }

    @BeforeAll
    fun createTable() = transaction(db) { SchemaUtils.create(Notes) }

    @AfterAll
    fun dropTable() = transaction(db) { SchemaUtils.drop(Notes) }

    @BeforeEach
    fun clearTable() {
        transaction(db) { Notes.deleteAll() }
    }

    @Nested
    inner class SingleTransaction {
        @Test
        fun `success commits`() = runTest {
            val result = tx.inTransaction {
                write("a")
                write("b")
                Result.success(Unit)
            }

            assertEquals(Result.success(Unit), result)
            assertEquals(listOf("a", "b"), committedNotes())
        }

        @Test
        fun `a Failure result rolls back everything written before it`() = runTest {
            val result = tx.inTransaction {
                write("a")
                Result.failure(Rejected)
            }

            assertEquals(Result.failure(Rejected), result)
            assertEquals(emptyList(), committedNotes())
        }

        @Test
        fun `an exception rolls back and is rethrown`() = runTest {
            assertFailsWith<IllegalStateException> {
                tx.inTransaction<Unit, Rejected> {
                    write("a")
                    error("database exploded")
                }
            }
            assertEquals(emptyList(), committedNotes())
        }

        @Test
        fun `a write the database refuses runs the block once, never again on its own`() = runTest {
            var runs = 0
            assertFailsWith<ExposedSQLException> {
                tx.inTransaction<Unit, Rejected> {
                    runs++
                    // A NULL text breaks the column's NOT NULL; Exposed itself would stop it before the database.
                    TransactionManager.current().exec("INSERT INTO tx_test_notes (text) VALUES (NULL)")
                    Result.success(Unit)
                }
            }
            assertEquals(1, runs)
        }

        @Test
        fun `stays one transaction across a suspension point`() = runTest {
            tx.inTransaction {
                write("before")
                yield() // the coroutine may continue on another thread here
                write("after")
                Result.failure(Rejected)
            }
            assertEquals(emptyList(), committedNotes())
        }
    }

    @Nested
    inner class NestedTransactions {
        @Test
        fun `inner joins outer, so an outer failure also undoes the inner writes`() = runTest {
            tx.inTransaction {
                write("outer")
                tx.inTransaction {
                    write("inner")
                    Result.success(Unit)
                }
                Result.failure(Rejected)
            }
            assertEquals(emptyList(), committedNotes())
        }

        @Test
        fun `inner failure passed up by the outer rolls back everything`() = runTest {
            val result = tx.inTransaction {
                write("outer")
                tx.inTransaction {
                    write("inner")
                    Result.failure(Rejected)
                }.unwrapOr { return@inTransaction Result.failure(it) }
                Result.success(Unit)
            }

            assertEquals(Result.failure(Rejected), result)
            assertEquals(emptyList(), committedNotes())
        }

        @Test
        fun `inner failure ignored by the outer is committed (only the outermost decides)`() = runTest {
            tx.inTransaction {
                write("outer")
                tx.inTransaction {
                    write("inner")
                    Result.failure(Rejected)
                }
                Result.success(Unit)
            }
            assertEquals(listOf("inner", "outer"), committedNotes())
        }

        @Test
        fun `both levels succeeding commits both writes`() = runTest {
            tx.inTransaction {
                write("outer")
                tx.inTransaction {
                    write("inner")
                    Result.success(Unit)
                }
            }
            assertEquals(listOf("inner", "outer"), committedNotes())
        }
    }
}
