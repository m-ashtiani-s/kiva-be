package com.kiva.domain.aggregate.user.model.valueobject

/**
 * All the roles of one user: always [Role.CUSTOMER], plus any panel roles.
 *
 * Kept in the order of [Role], whatever order they came in, so a response or a stored row lists them the same way
 * every time.
 */
@JvmInline
value class Roles private constructor(val values: Set<Role>) {

    // Second wall: [of] always adds CUSTOMER, so a set without it can only come from broken stored data. That is a
    // bug: stop loudly.
    init {
        require(Role.CUSTOMER in values) { "Roles without CUSTOMER: $values" }
    }

    /** Has a panel role: staff sessions are shorter, and the panel checks them on every request. */
    val isStaff: Boolean get() = values.any { it != Role.CUSTOMER }

    operator fun contains(role: Role): Boolean = role in values

    companion object {
        /** What every new account gets. */
        val CUSTOMER_ONLY: Roles = of(emptyList())

        /** [roles] plus CUSTOMER: it doesn't have to be in the list, and it can't be left out. */
        fun of(roles: Collection<Role>): Roles = Roles(inRoleOrder(roles + Role.CUSTOMER))

        /** For values read back from storage. Still checked by `init`: CUSTOMER must be there. */
        fun constructFromPersisted(roles: Collection<Role>): Roles = Roles(inRoleOrder(roles))

        private fun inRoleOrder(roles: Collection<Role>): Set<Role> = Role.entries.filterTo(LinkedHashSet()) { it in roles }
    }
}
