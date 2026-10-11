package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The body of `PUT /admin/customers/{customerId}/roles`: the whole list of panel roles; CUSTOMER needn't be in it. */
@Serializable
data class SetRolesHttpRequest(val roles: List<String>)
