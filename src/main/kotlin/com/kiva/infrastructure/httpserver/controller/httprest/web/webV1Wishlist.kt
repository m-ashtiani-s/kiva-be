package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.wishlist.usecase.command.AddToWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.ClearMyWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.RemoveFromWishlistUc
import com.kiva.application.usecase.wishlist.usecase.query.GetMyWishlistIdsUc
import com.kiva.application.usecase.wishlist.usecase.query.GetMyWishlistUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.KivaJson
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.WishlistAddHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.WishlistHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.WishlistIdsHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.WishlistToggleHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** The use cases of the wishlist, as the routes need them. */
class WishlistUseCases(
    val getMine: GetMyWishlistUc,
    val ids: GetMyWishlistIdsUc,
    val clear: ClearMyWishlistUc,
    val add: AddToWishlistUc,
    val remove: RemoveFromWishlistUc,
)

/** The path parameter of a saved product. */
private const val PRODUCT_ID = "productId"

/** The one order of the list (the contract has no `sort`): newest added first. */
private const val NEWEST = "newest"

/**
 * The customer's wishlist (contract tag `Wishlist`). Mounted under `/api/v1`; a login is needed, and the list is
 * always the token's customer's. A guest's hearts live in the browser: after logging in the client sends a `PUT` for
 * each (decision 1).
 */
fun Route.webV1Wishlist(useCases: WishlistUseCases, mediaUrls: MediaUrls, siteUrl: String) {
    authenticate(ACCESS) {
        get("/me/wishlist") {
            val pageable = call.pageable(allowedSorts = setOf(NEWEST), defaultSort = NEWEST, defaultSize = 48, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            call.respondResult(useCases.getMine.execute(GetMyWishlistUc.Qry(call.accessPrincipal().userId, pageable))) {
                WishlistHttpResponse.from(it, mediaUrls, siteUrl)
            }
        }

        delete("/me/wishlist") {
            call.respondResult(useCases.clear.execute(ClearMyWishlistUc.Cmd(call.accessPrincipal().userId)))
        }

        get("/me/wishlist/ids") {
            call.respondResult(useCases.ids.execute(GetMyWishlistIdsUc.Qry(call.accessPrincipal().userId))) { ids ->
                WishlistIdsHttpResponse(ids.map { it.value }, ids.size)
            }
        }

        // The body is optional: none, `{}` or `{"colorKey": "lilac"}`.
        put("/me/wishlist/items/{$PRODUCT_ID}") {
            val request = call.optionalBody(WishlistAddHttpRequest.serializer()) ?: WishlistAddHttpRequest()
            val command = AddToWishlistUc.Cmd(call.accessPrincipal().userId, call.parameters[PRODUCT_ID].orEmpty(), request.colorKey)
            call.respondResult(useCases.add.execute(command)) { WishlistToggleHttpResponse.from(it) }
        }

        delete("/me/wishlist/items/{$PRODUCT_ID}") {
            val command = RemoveFromWishlistUc.Cmd(call.accessPrincipal().userId, call.parameters[PRODUCT_ID].orEmpty())
            call.respondResult(useCases.remove.execute(command)) { WishlistToggleHttpResponse.from(it) }
        }
    }
}

/** A body that may be left out: null when there is none; one that can't be read is 400, as everywhere. */
internal suspend fun <T> RoutingCall.optionalBody(serializer: KSerializer<T>): T? {
    val body = receiveText()
    if (body.isBlank()) return null
    return try {
        KivaJson.decodeFromString(serializer, body)
    } catch (e: SerializationException) {
        throw BadRequestException("Unreadable body", e)
    }
}
