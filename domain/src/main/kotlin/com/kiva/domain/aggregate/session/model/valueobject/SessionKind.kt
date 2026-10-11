package com.kiva.domain.aggregate.session.model.valueobject

import com.kiva.domain.aggregate.user.model.valueobject.Roles

/** Which lifetimes a session has. Set at login from the user's roles and never changed afterwards. */
enum class SessionKind {
    /** A shopper: long sessions, so nobody has to log in every week. */
    CUSTOMER,

    /** Anyone with a panel role: short sessions, checked on every panel request. */
    PANEL,
    ;

    companion object {
        /** Staff get a PANEL session even when they are only shopping: one login, the stricter rules. */
        fun of(roles: Roles): SessionKind = if (roles.isStaff) PANEL else CUSTOMER
    }
}
