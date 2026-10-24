package com.kiva.application.usecase.blog.usecase.query

import com.kiva.application.dto.BlogCategoryDto
import com.kiva.application.usecase.blog.BlogPosts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import java.time.Clock

/**
 * The magazine's category chips (`GET /blog/categories`; public; roadmap phase 15, decision 11): every category in the
 * shop's order, each with the posts the shop sees in it now (a draft, a scheduled or an archived post not counted).
 */
class ListBlogCategoriesUc(
    private val transactionManager: TransactionManager,
    private val blogPosts: BlogPosts,
    private val clock: Clock,
) : QueryUseCase<ListBlogCategoriesUc.Qry, List<BlogCategoryDto>, ResultFailure> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<BlogCategoryDto>, ResultFailure> {
        logger.info("ListBlogCategoriesUc.execute")
        return transactionManager.inTransaction { Result.success(blogPosts.categoriesAt(clock.instant()).values.toList()) }
    }
}
