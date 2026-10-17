package com.kiva.infrastructure.common.persistence

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException

/** The name of the constraint the database refused a write with, or null when it said none. */
fun ExposedSQLException.refusedConstraint(): String? = (cause as? PSQLException)?.serverErrorMessage?.constraint

/** Whether the database refused a delete or an update because a foreign key still points at the row (SQLSTATE 23503). */
fun ExposedSQLException.isForeignKeyRefusal(): Boolean = (cause as? PSQLException)?.sqlState == FOREIGN_KEY_VIOLATION

private const val FOREIGN_KEY_VIOLATION = "23503"
