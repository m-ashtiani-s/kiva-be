package com.kiva.infrastructure.persistence

import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.exposedLogger
import org.jetbrains.exposed.v1.core.statements.StatementContext

/**
 * Writes every statement Exposed sends to the `Exposed` logger at DEBUG, with `?` where its values go.
 *
 * Exposed's own logger writes the values into the SQL, so every login would put a whole mobile number in the log, and
 * a profile edit a name and an email. The shape of a query is enough to follow what the code does; the values are the
 * customers' own data, and they stay out.
 */
object SqlWithoutValuesLogger : SqlLogger {

    override fun log(context: StatementContext, transaction: Transaction) {
        if (exposedLogger.isDebugEnabled) exposedLogger.debug(context.sql(transaction))
    }
}
