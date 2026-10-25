package com.kiva.infrastructure.persistence.exposed.tbl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** The blog's categories, made by V40__blog.sql with SQL only (roadmap phase 15, decision 1). */
object BlogCategoryTbl : LongIdTable(name = "blogcategory_tbl", columnName = "pId") {
    val slug = varchar("slug", 60).uniqueIndex()
    val name = varchar("name", 60)
    val sortOrder = integer("sortOrder")
}

/** The blog's authors, made by V40__blog.sql with SQL only; at most one `isDefault`. */
object BlogAuthorTbl : LongIdTable(name = "blogauthor_tbl", columnName = "pId") {
    val name = varchar("name", 60)
    val title = varchar("title", 80).nullable()
    val bio = varchar("bio", 500).nullable()
    val avatarMediaId = varchar("avatarMediaId", 26).nullable()
    val isDefault = bool("isDefault")
}

/** The blog's posts. Created by V40__blog.sql (Exposed never creates tables); keep both in step. */
object BlogPostTbl : LongIdTable(name = "blogpost_tbl", columnName = "pId") {
    val postId = long("postId").uniqueIndex()
    val slug = varchar("slug", 100)
    val title = varchar("title", 120)
    val excerpt = varchar("excerpt", 300).nullable()
    val lead = varchar("lead", 600).nullable()
    val categoryId = long("categoryId")
    val authorId = long("authorId")
    val coverMediaId = varchar("coverMediaId", 26).nullable()
    val coverBackground = varchar("coverBackground", 7).nullable()
    val readingMinutes = short("readingMinutes").nullable()
    val blocks = jsonb<List<BlogBlockPayload>>("blocks", Json)
    val tags = array<String>("tags")
    /** The title, excerpt, tags and category's name, normalizePersian'd: what `q` looks in. */
    val searchText = text("searchText")
    val isFeatured = bool("isFeatured")
    val status = varchar("status", 10)
    val publishedAt = timestampWithTimeZone("publishedAt").nullable()
    val seoTitle = varchar("seoTitle", 70).nullable()
    val seoDescription = varchar("seoDescription", 170).nullable()
    val seoNoIndex = bool("seoNoIndex")
    val createdOn = timestampWithTimeZone("createdOn")
    val createdBy = long("createdBy")
    val updatedOn = timestampWithTimeZone("updatedOn")
    val updatedBy = long("updatedBy")

    /** The unique key on [slug] as V40 named it; the database names it when it refuses a save. */
    const val SLUG_KEY = "blogpost_slug_key"

    /** Hands out post ids before a post is stored. */
    const val ID_SEQUENCE = "blogpostid_seq"

    /** The post has [tag]: Postgres `@>` on [tags]. */
    fun hasTag(tag: String): Op<Boolean> = Contains(tags, listOf(tag))

    private class Contains(column: Column<List<String>>, values: List<String>) : ComparisonOp(column, QueryParameter(values, column.columnType), "@>")
}

/**
 * One block of a post as stored in `blogpost_tbl.blocks` (roadmap phase 15, decision 7): [type] and only that kind's
 * fields; the others are null. Never renamed: the stored JSON reads back through this class.
 *
 * @property text a heading's, quote's or plain paragraph's or tip's words, or a photo's caption.
 */
@Serializable
data class BlogBlockPayload(
    val type: String,
    val anchor: String? = null,
    val text: String? = null,
    val html: String? = null,
    val title: String? = null,
    val items: List<String>? = null,
    val productId: Long? = null,
    val imageMediaId: String? = null,
)

/** «این مطلب مفید بود؟»: one vote per post and voter. Created by V40__blog.sql. */
object BlogFeedbackTbl : Table("blogfeedback_tbl") {
    val postId = long("postId")
    val voterKey = varchar("voterKey", 70)
    val helpful = bool("helpful")
    val comment = varchar("comment", 300).nullable()
    val createdOn = timestampWithTimeZone("createdOn")

    override val primaryKey = PrimaryKey(postId, voterKey)
}
