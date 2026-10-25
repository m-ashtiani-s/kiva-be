package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.AdminBlogAuthorDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase

/**
 * The panel's author picker (`GET /admin/blog/authors`; ADMIN, CONTENT_EDITOR; contract 1.22.0): every byline, whether
 * or not it has a post in the shop yet, the default first. Authors are made with SQL (roadmap phase 15, decision 1);
 * this only reads them, so the form can offer one that has never signed a published post.
 */
class ListBlogAuthorsUc(
    private val transactionManager: TransactionManager,
    private val blogPosts: BlogPosts,
) : QueryUseCase<ListBlogAuthorsUc.Qry, List<AdminBlogAuthorDto>, ResultFailure> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<AdminBlogAuthorDto>, ResultFailure> {
        logger.info("ListBlogAuthorsUc.execute")
        return transactionManager.inTransaction { Result.success(blogPosts.authors()) }
    }
}
