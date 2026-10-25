package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.faq.entity.FaqBook
import com.kiva.domain.aggregate.faq.model.valueobject.FaqAnchor
import com.kiva.domain.aggregate.faq.model.valueobject.FaqItemId
import com.kiva.domain.aggregate.faq.repository.FaqRepository
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.infrastructure.persistence.exposed.tbl.FaqGroupTbl
import com.kiva.infrastructure.persistence.exposed.tbl.FaqItemTbl
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** Runs inside the use case's transaction; it never opens one itself. */
class FaqRepositoryImpl : FaqRepository {

    override fun lock() {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", listOf(TextColumnType() to FAQ_LOCK)) { }
    }

    override fun load(): FaqBook {
        val questions = FaqItemTbl.selectAll().orderBy(FaqItemTbl.sortOrder to SortOrder.ASC).groupBy({ it[FaqItemTbl.groupId].value }) {
            FaqBook.Question(FaqItemId.constructFromPersisted(it[FaqItemTbl.itemId]), it[FaqItemTbl.question], it[FaqItemTbl.answer])
        }
        return FaqBook(
            FaqGroupTbl.selectAll().orderBy(FaqGroupTbl.sortOrder to SortOrder.ASC).map { row ->
                FaqBook.Group(
                    anchor = FaqAnchor.constructFromPersisted(row[FaqGroupTbl.anchor]),
                    name = row[FaqGroupTbl.name],
                    icon = row[FaqGroupTbl.icon]?.let(IconName::constructFromPersisted),
                    isSignature = row[FaqGroupTbl.isSignature],
                    questions = questions[row[FaqGroupTbl.id].value].orEmpty(),
                )
            },
        )
    }

    override fun nextItemId(): FaqItemId =
        TransactionManager.current().exec("SELECT nextval('${FaqItemTbl.ITEM_ID_SEQUENCE}')") { rows ->
            rows.next()
            FaqItemId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${FaqItemTbl.ITEM_ID_SEQUENCE}') returned nothing")

    /** Under [lock]: the old groups go (their questions with them, ON DELETE CASCADE) and [book] is written in its order. */
    override fun save(book: FaqBook) {
        FaqGroupTbl.deleteAll()
        book.groups.forEachIndexed { g, group ->
            val groupId = FaqGroupTbl.insertAndGetId {
                it[anchor] = group.anchor.value
                it[name] = group.name
                it[icon] = group.icon?.value
                it[isSignature] = group.isSignature
                it[sortOrder] = g
            }
            FaqItemTbl.batchInsert(group.questions.withIndex()) { (q, question) ->
                this[FaqItemTbl.itemId] = question.id.value
                this[FaqItemTbl.groupId] = groupId
                this[FaqItemTbl.question] = question.question
                this[FaqItemTbl.answer] = question.answer
                this[FaqItemTbl.sortOrder] = q
            }
        }
    }

    private companion object {
        const val FAQ_LOCK = "faq"
    }
}
