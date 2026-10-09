package com.kiva.common.event

import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName

/**
 * Saving an aggregate was refused because someone else saved a change to it after it was read.
 *
 * Both changes took the same next number in the aggregate's stream and the event store refused the second one (or,
 * for an aggregate kept in a plain table such as a user, the row no longer had the version that was read), so
 * nothing of it was stored. It is not a business failure (nothing the user asked for was wrong), so it is not a Result:
 * the transaction rolls back. The caller can read the aggregate again and redo the change (stock), or tell the user
 * to look at the new state first (an admin form).
 */
class ConcurrentChangeException(
    val aggregateName: EventAggregateName,
    val aggregateId: EventAggregateId,
    cause: Throwable? = null,
) : RuntimeException("${aggregateName.value} ${aggregateId.value} was changed by someone else after it was read", cause)
