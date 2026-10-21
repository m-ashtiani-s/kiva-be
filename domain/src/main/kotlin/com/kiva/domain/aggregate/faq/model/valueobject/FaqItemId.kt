package com.kiva.domain.aggregate.faq.model.valueobject

/** A FAQ question's id (the contract's `FaqItem.id`), given once and kept across saves; a positive number. */
@JvmInline
value class FaqItemId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid FaqItemId $value" }
    }

    companion object {
        /** For an id read back from storage or given out by the repository. Still checked by `init`. */
        fun constructFromPersisted(value: Long): FaqItemId = FaqItemId(value)
    }
}
