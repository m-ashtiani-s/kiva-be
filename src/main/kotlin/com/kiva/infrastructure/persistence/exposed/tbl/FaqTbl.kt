package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** The FAQ's groups. Created by V31__content_basics.sql (Exposed never creates tables); keep them in step. */
object FaqGroupTbl : LongIdTable(name = "faqgroup_tbl", columnName = "pId") {
    /** The contract's `FaqGroup.id`. */
    val anchor = varchar("anchor", 31).uniqueIndex()
    val name = varchar("name", 60)
    val icon = varchar("icon", 30).nullable()
    val isSignature = bool("isSignature")
    val sortOrder = integer("sortOrder")
}

/** The FAQ's questions. Created by V31__content_basics.sql; keep them in step. */
object FaqItemTbl : LongIdTable(name = "faqitem_tbl", columnName = "pId") {
    /** The question's own id, kept across saves (the contract's `FaqItem.id`). */
    val itemId = long("itemId").uniqueIndex()
    val groupId = reference("groupId", FaqGroupTbl, onDelete = ReferenceOption.CASCADE)
    val question = varchar("question", 300)
    /** The sanitized HTML. */
    val answer = text("answer")
    val sortOrder = integer("sortOrder")

    /** V31's sequence of question ids. */
    const val ITEM_ID_SEQUENCE = "faqitemid_seq"
}
