package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.blog.usecase.query.GetBlogPostUc
import com.kiva.application.usecase.blog.usecase.query.ListBlogCategoriesUc
import com.kiva.application.usecase.blog.usecase.query.ListBlogPostsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogCategoryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogPostDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogPostListHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the shop's magazine, as the routes need them. */
class BlogUseCases(
    val listCategories: ListBlogCategoriesUc,
    val listPosts: ListBlogPostsUc,
    val getPost: GetBlogPostUc,
)

/** The magazine's page size (contract: 9 by default, at most 30). */
private const val BLOG_PAGE_SIZE = 9
private const val BLOG_MAX_PAGE_SIZE = 30

/**
 * The shop's magazine (contract tag `Blog`; roadmap phase 15): the categories, the list with the featured post, and a
 * post's page. Mounted under `/api/v1`; no login. The answers are the same for everyone and hold only what the shop sees,
 * so they are cached (`ETag`, `max-age=300`; decision 16).
 */
fun Route.webV1Blog(useCases: BlogUseCases, siteUrl: String, mediaUrls: MediaUrls) {
    get("/blog/categories") {
        call.respondResultCacheable(useCases.listCategories.execute(ListBlogCategoriesUc.Qry)) { categories -> categories.map(BlogCategoryHttpResponse::from) }
    }

    get("/blog/posts") {
        val pageable = call.pageable(defaultSize = BLOG_PAGE_SIZE, maxSize = BLOG_MAX_PAGE_SIZE).unwrapOr { return@get call.respondFailure(it) }
        val params = call.request.queryParameters
        val query = ListBlogPostsUc.Qry(params["category"], params["q"], params["tag"], pageable)
        call.respondResultCacheable(useCases.listPosts.execute(query)) { BlogPostListHttpResponse.from(it, mediaUrls) }
    }

    get("/blog/posts/{idOrSlug}") {
        val query = GetBlogPostUc.Qry(call.parameters["idOrSlug"].orEmpty())
        call.respondResultCacheable(useCases.getPost.execute(query)) { BlogPostDetailHttpResponse.from(it, siteUrl, mediaUrls) }
    }
}
