package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The contract's optional body of `POST /auth/logout`. No body is `allDevices = false`. */
@Serializable
data class LogoutHttpRequest(val allDevices: Boolean = false)
