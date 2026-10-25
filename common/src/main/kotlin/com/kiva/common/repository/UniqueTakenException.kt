package com.kiva.common.repository

/**
 * Saving a row was refused because another request took one of its unique values (a slug, a key) after the caller
 * checked that it was free.
 *
 * The use cases check first, so only two requests at the same moment get here: both pass the check, the first saves,
 * and the database's unique index refuses the second. It is a race, not a bug, so it never ends in a 500; but it is
 * not a Result either, because nothing can go on after the database refused a write. The transaction rolls back and
 * the use case answers what its own check would have answered (the same 409).
 *
 * @property what the value that was taken, for the log only (`category slug shoulder`).
 */
class UniqueTakenException(val what: String, cause: Throwable) : RuntimeException("$what was taken by another request", cause)

/**
 * Removing a row was refused because something still points at it (a product at its category): another request added
 * the reference after the caller counted none. The database's foreign key is the second wall behind that count; the
 * use case answers the same 409 IN_USE as its own check.
 */
class StillReferencedException(val what: String, cause: Throwable) : RuntimeException("$what is still referenced", cause)
