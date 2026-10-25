package com.kiva.domain.aggregate.campaign.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import java.time.Instant

/** The id of a campaign (its row's), given out by the repository when the panel makes one. */
@JvmInline
value class CampaignId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid CampaignId $value" }
    }

    companion object {
        /** An id as a path sent it; the panel answers a malformed one as it answers one nobody has (404). */
        fun makeNew(raw: Long): Result<CampaignId, Failure> = if (raw > 0) Result.success(CampaignId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): CampaignId = CampaignId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("CAMPAIGN_ID_INVALID")
    }
}

/**
 * When a campaign runs: from [startsAt] until (not at) [endsAt], both always there (the countdown counts to [endsAt]).
 * CampaignDraft refuses an end not after the start with DATE_RANGE_INVALID; here it is only the second wall.
 */
data class CampaignWindow(val startsAt: Instant, val endsAt: Instant) {

    init {
        require(endsAt.isAfter(startsAt)) { "A campaign ends at $endsAt, not after it starts at $startsAt" }
    }

    fun contains(now: Instant): Boolean = !now.isBefore(startsAt) && now.isBefore(endsAt)

    /** The two share a moment: `[a, b)` and `[c, d)` meet when a < d and c < b (the table's `tstzrange(...) &&`). */
    fun overlaps(other: CampaignWindow): Boolean = startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt)
}
