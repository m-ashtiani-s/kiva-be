package com.kiva.domain.aggregate.faq.repository

import com.kiva.domain.aggregate.faq.entity.FaqBook
import com.kiva.domain.aggregate.faq.model.valueobject.FaqItemId

/** The FAQ, read and written whole. Runs inside the use case's transaction. */
interface FaqRepository {

    /** Holds the FAQ until the transaction ends (an advisory lock), so two saves run one after the other. */
    fun lock()

    /** The whole FAQ; [FaqBook.EMPTY] before anyone wrote it. */
    fun load(): FaqBook

    /** A new question's id, never given before. */
    fun nextItemId(): FaqItemId

    /** Replaces the stored FAQ with [book]. */
    fun save(book: FaqBook)
}
