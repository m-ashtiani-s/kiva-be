package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.blog.usecase.command.SendBlogFeedbackUc
import com.kiva.application.usecase.blog.usecase.query.GetBlogPostUc
import com.kiva.application.usecase.blog.usecase.query.ListBlogCategoriesUc
import com.kiva.application.usecase.blog.usecase.query.ListBlogPostsUc
import com.kiva.application.usecase.blog.usecase.query.ListRelatedBlogPostsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.AccessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.BlogFeedbackHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogCategoryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogPostDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogPostListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BlogPostSummaryHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlin.time.Duration.Companion.hours

/** The limit per address of `POST /blog/posts/{postId}/feedback` (registered in configureHttp; roadmap phase 15, decision 14). */
val BLOG_FEEDBACK_RATE_LIMIT = RateLimitName("blog-feedback")
const val BLOG_FEEDBACK_PER_IP = 30
val BLOG_FEEDBACK_WINDOW = 1.hours

/** The use cases of the shop's magazine, as the routes need them. */
class BlogUseCases(
    val listCategories: ListBlogCategoriesUc,
    val listPosts: ListBlogPostsUc,
    val getPost: GetBlogPostUc,
    val listRelated: ListRelatedBlogPostsUc,
    val sendFeedback: SendBlogFeedbackUc,
)

/** The magazine's page size (contract: 9 by default, at most 30). */
private const val BLOG_PAGE_SIZE = 9
private const val BLOG_MAX_PAGE_SIZE = 30

/**
 * The shop's magazine (contract tag `Blog`; roadmap phase 15): the categories, the list with the featured post, a post's
 * page and its related posts, and «مفید بود؟». Mounted under `/api/v1`. The reads need no login; they are the same for
 * everyone and hold only what the shop sees, so they are cached (`ETag`, `max-age=300`; decision 16). A vote takes a login
 * if there is one (a broken token is 401, never a guest) and is limited per address.
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

    get("/blog/posts/{postId}/related") {
        val query = ListRelatedBlogPostsUc.Qry(call.parameters["postId"].orEmpty(), call.request.queryParameters["limit"])
        call.respondResultCacheable(useCases.listRelated.execute(query)) { posts -> posts.map { BlogPostSummaryHttpResponse.from(it, mediaUrls) } }
    }

    rateLimit(BLOG_FEEDBACK_RATE_LIMIT) {
        authenticate(ACCESS, optional = true) {
            post("/blog/posts/{postId}/feedback") {
                val request = call.receive<BlogFeedbackHttpRequest>()
                val voter = call.principal<AccessPrincipal>()?.userId
                val command = SendBlogFeedbackUc.Cmd(call.parameters["postId"].orEmpty(), request.helpful, request.comment, voter, call.request.origin.remoteAddress)
                call.respondResult(useCases.sendFeedback.execute(command))
            }
        }
    }
}
