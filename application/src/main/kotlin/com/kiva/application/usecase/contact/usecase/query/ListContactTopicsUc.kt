package com.kiva.application.usecase.contact.usecase.query

import com.kiva.common.railway.Result
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic

/**
 * The contact form's topics (`GET /contact/topics`, public, cached for 5 minutes; roadmap phase 13, decision 1): the
 * five, in the contract's order. Their labels are the route's (ContactTexts). Nothing is read from the database.
 */
class ListContactTopicsUc : QueryUseCase<ListContactTopicsUc.Qry, List<ContactTopic>, Nothing> {

    data object Qry

    override suspend fun execute(query: Qry): Result<List<ContactTopic>, Nothing> = Result.success(ContactTopic.entries)
}
