package com.kiva.domain.aggregate.session.model.valueobject

/**
 * Where a session was started, for showing a user their sessions one day («Chrome on Android»). Only for display, never
 * for security: all of it comes from the client and can be anything.
 *
 * So nothing here is refused: each text is trimmed, its control characters dropped, cut to what the table holds, and
 * an empty one is no text.
 */
// copy() is as private as the constructor, so nothing gets past the cleaning in [of].
@ConsistentCopyVisibility
data class SessionDevice private constructor(val name: String?, val userAgent: String?, val ip: String?) {

    companion object {
        const val MAX_NAME = 100
        const val MAX_USER_AGENT = 255
        const val MAX_IP = 45

        val UNKNOWN = SessionDevice(null, null, null)

        fun of(name: String?, userAgent: String?, ip: String?): SessionDevice =
            SessionDevice(name.cleaned(MAX_NAME), userAgent.cleaned(MAX_USER_AGENT), ip.cleaned(MAX_IP))

        /** For values read back from storage, already cleaned when they were stored. */
        fun constructFromPersisted(name: String?, userAgent: String?, ip: String?): SessionDevice = SessionDevice(name, userAgent, ip)

        private fun String?.cleaned(max: Int): String? = this?.filterNot(Char::isISOControl)?.trim()?.take(max)?.trim()?.ifEmpty { null }
    }
}
