package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.blog.usecase.command.CreateBlogPostUc
import com.kiva.application.usecase.blog.usecase.command.DeleteBlogPostUc
import com.kiva.application.usecase.blog.usecase.command.UpdateBlogPostUc
import com.kiva.application.usecase.blog.usecase.query.FindAdminBlogPostsUc
import com.kiva.application.usecase.blog.usecase.query.GetAdminBlogPostUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.BlogPostHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminBlogPostHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's blog, as the routes need them. */
class AdminBlogUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findPosts: FindAdminBlogPostsUc,
    val getPost: GetAdminBlogPostUc,
    val createPost: CreateBlogPostUc,
    val updatePost: UpdateBlogPostUc,
    val deletePost: DeleteBlogPostUc,
)

/** The path parameter of one post. */
private const val POST_ID = "postId"

/**
 * The panel's blog (contract tag `Admin · Content`, `[ADMIN, CONTENT_EDITOR]`; roadmap phase 15): every post in any
 * status, one with all its blocks, and writing, rewriting and deleting one. Mounted under `/api/v1`. The answer of a
 * save is the post as GET gives it.
 */
fun Route.webV1AdminBlog(useCases: AdminBlogUseCases, siteUrl: String, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CONTENT_EDITOR) {
        get("/admin/blog/posts") {
            val pageable = call.pageable().unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = FindAdminBlogPostsUc.Qry(params["status"], params["q"], params["category"], pageable)
            call.respondResult(useCases.findPosts.execute(query)) { page -> page.toHttpResponse { AdminBlogPostHttpResponse.from(it, siteUrl, mediaUrls) } }
        }

        get("/admin/blog/posts/{$POST_ID}") {
            val query = GetAdminBlogPostUc.Qry(call.parameters[POST_ID].orEmpty())
            call.respondResult(useCases.getPost.execute(query)) { AdminBlogPostHttpResponse.from(it, siteUrl, mediaUrls) }
        }

        post("/admin/blog/posts") {
            val request = call.receive<BlogPostHttpRequest>()
            val id = useCases.createPost.execute(CreateBlogPostUc.Cmd(request.toFields(), request.publishedAt, call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getPost.execute(GetAdminBlogPostUc.Qry(id.value.toString())), HttpStatusCode.Created) { AdminBlogPostHttpResponse.from(it, siteUrl, mediaUrls) }
        }

        put("/admin/blog/posts/{$POST_ID}") {
            val request = call.receive<BlogPostHttpRequest>()
            val command = UpdateBlogPostUc.Cmd(call.parameters[POST_ID].orEmpty(), request.toFields(), request.publishedAt, call.panelActor())
            val id = useCases.updatePost.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getPost.execute(GetAdminBlogPostUc.Qry(id.value.toString()))) { AdminBlogPostHttpResponse.from(it, siteUrl, mediaUrls) }
        }

        delete("/admin/blog/posts/{$POST_ID}") {
            call.respondResult(useCases.deletePost.execute(DeleteBlogPostUc.Cmd(call.parameters[POST_ID].orEmpty(), call.panelActor())))
        }
    }
}
