package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.railway.Result
import com.kiva.common.repository.UniqueTakenException
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.DiscountTerms
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountCodeTbl
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The panel's writes of discount codes on kiva_test: the version and the unique text are the last walls (step 4.2). */
class DiscountCodeRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = DiscountCodeRepositoryImpl()
    private val now = Instant.parse("2026-10-20T08:00:00Z")

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) { DiscountCodeTbl.deleteAll() }
    }

    private fun terms(code: String, value: Long = 10) = DiscountTerms.makeNew(
        code, "PERCENT", value, "x", null, null, Result.success(null), Result.success(null), null, null, null, null, null, null, null, null,
    ).getOrNull()!!

    private fun made(code: String): DiscountCode = transaction(db) {
        DiscountCode.create(repository.nextId(), terms(code), now).also { repository.add(it) }
    }

    @Test
    fun `a code is read back as it was made, and a save counts its version up`() {
        val code = made("REPO10")

        transaction(db) { repository.save(code.change(terms("REPO10", 20), now.plusSeconds(1))!!) }

        val stored = transaction(db) { repository.findById(code.id) }!!
        assertEquals(20, stored.value)
        assertEquals(1, stored.version)
        assertEquals(now, stored.createdOn)
        assertEquals(now.plusSeconds(1), stored.updatedOn)
    }

    @Test
    fun `a save over a version another save moved writes nothing`() {
        val code = made("REPO10")
        transaction(db) { repository.save(code.change(terms("REPO10", 20), now)!!) }

        assertFailsWith<ConcurrentChangeException> { transaction(db) { repository.save(code.change(terms("REPO10", 30), now)!!) } }
        assertEquals(20, transaction(db) { repository.findById(code.id) }!!.value)
    }

    @Test
    fun `a text another code has is a UniqueTakenException, for a new code and for an edit`() {
        made("TAKEN")
        val other = made("OTHER")

        assertFailsWith<UniqueTakenException> { made("TAKEN") }
        assertFailsWith<UniqueTakenException> { transaction(db) { repository.save(other.change(terms("TAKEN"), now)!!) } }
    }
}
