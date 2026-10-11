package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.AddressHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AddressHttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the address book, as the routes need them. */
class AddressUseCases(
    val listMyAddresses: ListMyAddressesUc,
    val getMyAddress: GetMyAddressUc,
    val addAddress: AddAddressUc,
    val updateAddress: UpdateAddressUc,
    val deleteAddress: DeleteAddressUc,
    val setDefaultAddress: SetDefaultAddressUc,
)

/** The path parameter every address route names its address by. */
private const val ADDRESS_ID = "addressId"

/**
 * The logged-in customer's address book (contract tag `Addresses`). Mounted under `/api/v1`; every route needs a login,
 * and the book is always the token's user's. After a change the answer is built by a query, as everywhere.
 */
fun Route.webV1Addresses(useCases: AddressUseCases) {
    authenticate(ACCESS) {
        get("/me/addresses") {
            val userId = call.accessPrincipal().userId
            call.respondResult(useCases.listMyAddresses.execute(ListMyAddressesUc.Qry(userId))) { it.map(AddressHttpResponse::from) }
        }

        post("/me/addresses") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<AddressHttpRequest>()
            val addressId = useCases.addAddress.execute(AddAddressUc.Cmd(userId, request.toFields(), request.setAsDefault))
                .unwrapOr { return@post call.respondFailure(it) }
            val query = GetMyAddressUc.Qry(userId, addressId.value.toString())
            call.respondResult(useCases.getMyAddress.execute(query), HttpStatusCode.Created) { AddressHttpResponse.from(it) }
        }

        get("/me/addresses/{$ADDRESS_ID}") {
            val query = GetMyAddressUc.Qry(call.accessPrincipal().userId, call.addressIdSegment())
            call.respondResult(useCases.getMyAddress.execute(query)) { AddressHttpResponse.from(it) }
        }

        put("/me/addresses/{$ADDRESS_ID}") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<AddressHttpRequest>()
            val addressId = useCases.updateAddress.execute(UpdateAddressUc.Cmd(userId, call.addressIdSegment(), request.toFields(), request.setAsDefault))
                .unwrapOr { return@put call.respondFailure(it) }
            val query = GetMyAddressUc.Qry(userId, addressId.value.toString())
            call.respondResult(useCases.getMyAddress.execute(query)) { AddressHttpResponse.from(it) }
        }

        delete("/me/addresses/{$ADDRESS_ID}") {
            call.respondResult(useCases.deleteAddress.execute(DeleteAddressUc.Cmd(call.accessPrincipal().userId, call.addressIdSegment())))
        }

        // The answer is the whole book, as the contract asks: the default changed for two addresses at once.
        post("/me/addresses/{$ADDRESS_ID}/default") {
            val userId = call.accessPrincipal().userId
            useCases.setDefaultAddress.execute(SetDefaultAddressUc.Cmd(userId, call.addressIdSegment()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.listMyAddresses.execute(ListMyAddressesUc.Qry(userId))) { it.map(AddressHttpResponse::from) }
        }
    }
}

// Always present: the routes only match when the segment is there.
private fun RoutingCall.addressIdSegment(): String = pathParameters[ADDRESS_ID].orEmpty()
