package com.kiva.infrastructure.httpserver.auth

import com.kiva.application.port.PanelActor
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

/**
 * Who makes this panel request, from where: the access token's user and the client's address (the one nginx set, with
 * `KIVA_TRUSTED_PROXY_HOPS = 1`). Never from the body. Every panel write gives it to its use case for the change log.
 */
fun ApplicationCall.panelActor(): PanelActor = PanelActor(accessPrincipal().userId, request.origin.remoteAddress)
