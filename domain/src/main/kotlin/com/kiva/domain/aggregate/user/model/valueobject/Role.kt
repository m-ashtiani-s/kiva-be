package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * What a user may do. Values and meanings from the contract's UserRole; which panel endpoint takes which role is each
 * endpoint's `x-roles` in the contract.
 */
enum class Role {
    /** Shopping, orders and the account. Every user has it, and it can't be taken away. */
    CUSTOMER,

    /** Everything in the panel, including giving roles, refunds and discount codes. */
    ADMIN,

    /** Products and stock. */
    CATALOG_MANAGER,

    /** Orders and shipping. */
    OPERATOR,

    /** Content and banners. */
    CONTENT_EDITOR,

    /** Reviews, contact messages and customers. */
    SUPPORT,
    ;

    companion object {
        /** The role as the contract sends it (`OPERATOR`), exactly; anything else is ROLE_INVALID. */
        fun makeNew(raw: String): Result<Role, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field here: the caller names it (`roles.0`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("ROLE_INVALID")
    }
}
