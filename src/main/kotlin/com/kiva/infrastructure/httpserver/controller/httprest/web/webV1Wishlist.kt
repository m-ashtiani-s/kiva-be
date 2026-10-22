package com.kiva.infrastructure.httpserver.controller.httprest.web

import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SharedWishlistHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.WishlistShareHttpResponse
import com.kiva.application.usecase.wishlist.usecase.query.GetSharedWishlistUc
import com.kiva.application.usecase.wishlist.usecase.command.RevokeWishlistShareUc
import com.kiva.application.usecase.wishlist.usecase.command.ShareMyWishlistUc
import io.ktor.server.routing.post
import io.ktor.server.response.respond
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CartHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.WishlistAddToCartHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.WishlistToCartHttpRequest
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.wishlist.usecase.command.AddWishlistToCartUc
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
    val toCart: AddWishlistToCartUc,
    val getCart: GetCartUc,
    val share: ShareMyWishlistUc,
    val revokeShare: RevokeWishlistShareUc,
    val getShared: GetSharedWishlistUc,
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

        // The customer's cart after it: never refused for want of stock; what couldn't go is in `skipped`.
        post("/me/wishlist/add-to-cart") {
            val request = call.optionalBody(WishlistToCartHttpRequest.serializer()) ?: WishlistToCartHttpRequest()
            val added = useCases.toCart.execute(AddWishlistToCartUc.Cmd(call.accessPrincipal().userId, request.productIds))
                .unwrapOr { return@post call.respondFailure(it) }
            val cart = useCases.getCart.execute(GetCartUc.Qry(added.change.cartId.value)).unwrapOr { return@post call.respondFailure(it) }
            call.respond(WishlistAddToCartHttpResponse.from(CartHttpResponse.from(cart, added.change.issues, mediaUrls), added))
        }

        post("/me/wishlist/share") {
            call.respondResult(useCases.share.execute(ShareMyWishlistUc.Cmd(call.accessPrincipal().userId))) { WishlistShareHttpResponse.from(it, siteUrl) }
        }

        delete("/me/wishlist/share") {
            call.respondResult(useCases.revokeShare.execute(RevokeWishlistShareUc.Cmd(call.accessPrincipal().userId)))
        }

        delete("/me/wishlist/items/{$PRODUCT_ID}") {
            val command = RemoveFromWishlistUc.Cmd(call.accessPrincipal().userId, call.parameters[PRODUCT_ID].orEmpty())
            call.respondResult(useCases.remove.execute(command)) { WishlistToggleHttpResponse.from(it) }
        }
    }
}

/** The path parameter of a shared list. */
private const val TOKEN = "token"

/**
 * Someone's shared wishlist, public (decision 14): never cached anywhere, since the owner may revoke it any moment and
 * the answer is somebody's list. nginx limits it to 30 a minute per IP.
 */
fun Route.webV1SharedWishlist(getShared: GetSharedWishlistUc, mediaUrls: MediaUrls) {
    get("/wishlists/shared/{$TOKEN}") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondResult(getShared.execute(GetSharedWishlistUc.Qry(call.parameters[TOKEN].orEmpty()))) { SharedWishlistHttpResponse.from(it, mediaUrls) }
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
