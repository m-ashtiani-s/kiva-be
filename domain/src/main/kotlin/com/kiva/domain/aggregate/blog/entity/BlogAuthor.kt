package com.kiva.domain.aggregate.blog.entity

import com.kiva.domain.aggregate.blog.model.valueobject.BlogAuthorId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId

/**
 * A byline of the blog («تیم کیوا»), read only: made and changed with SQL (roadmap phase 15, decision 1). Not an
 * account: a post's author is a name the shop signs with, not the staff member who saved it.
 *
 * @property isDefault the author of a post whose form names none; at most one (a unique index).
 */
class BlogAuthor private constructor(
    val id: BlogAuthorId,
    val name: String,
    val title: String?,
    val bio: String?,
    val avatarMediaId: MediaId?,
    val isDefault: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Blog author ${id.value} has no name" }
    }

    override fun equals(other: Any?): Boolean = other is BlogAuthor && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "BlogAuthor(id=${id.value})"

    companion object {
        fun constructFromPersisted(id: BlogAuthorId, name: String, title: String?, bio: String?, avatarMediaId: MediaId?, isDefault: Boolean): BlogAuthor =
            BlogAuthor(id, name, title, bio, avatarMediaId, isDefault)
    }
}
