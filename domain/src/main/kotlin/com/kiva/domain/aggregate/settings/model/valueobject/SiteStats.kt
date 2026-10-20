package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.requiredIn
import com.kiva.common.railway.under
import java.math.BigDecimal

/**
 * The shop's marketing numbers (the contract's SiteStats), typed by hand in the panel's settings and shown on the about
 * page (`GET /site/stats`, phase 11). Nothing here is counted from orders (roadmap phase 4, decisions 7 and 20).
 *
 * @property photoMatchSatisfactionPercent 0 to 100; the counts 0 to 10,000,000.
 * @property satisfaction the summary of what customers said; null while the shop shows none.
 */
@ConsistentCopyVisibility
data class SiteStats private constructor(
    val happyCustomers: Int,
    val preShipmentPhotos: Int,
    val photoMatchSatisfactionPercent: Int,
    val productModels: Int,
    val satisfaction: Satisfaction?,
) {
    init {
        require(happyCustomers in COUNT && preShipmentPhotos in COUNT && productModels in COUNT && photoMatchSatisfactionPercent in PERCENT) {
            "Invalid SiteStats $this"
        }
    }

    companion object {
        val COUNT = 0..10_000_000
        val PERCENT = 0..100

        /** Before anything was typed (V30's default): every number 0, no satisfaction. */
        val NONE = SiteStats(0, 0, 0, 0, null)

        /** Every problem at once, each under its own field (`photoMatchSatisfactionPercent`, `satisfaction.metrics.1.percent`). */
        fun makeNew(
            happyCustomers: Int?,
            preShipmentPhotos: Int?,
            photoMatchSatisfactionPercent: Int?,
            productModels: Int?,
            satisfaction: Result<Satisfaction?, ResultFailure>,
        ): Result<SiteStats, InputProblems> {
            val problems = Problems()
            val customers = problems.take(happyCustomers.requiredIn(COUNT).forField("happyCustomers"))
            val photos = problems.take(preShipmentPhotos.requiredIn(COUNT).forField("preShipmentPhotos"))
            val match = problems.take(photoMatchSatisfactionPercent.requiredIn(PERCENT).forField("photoMatchSatisfactionPercent"))
            val models = problems.take(productModels.requiredIn(COUNT).forField("productModels"))
            val summary = problems.take(satisfaction)
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(SiteStats(customers.value, photos.value, match.value, models.value, summary.value))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            happyCustomers: Int,
            preShipmentPhotos: Int,
            photoMatchSatisfactionPercent: Int,
            productModels: Int,
            satisfaction: Satisfaction?,
        ): SiteStats = SiteStats(happyCustomers, preShipmentPhotos, photoMatchSatisfactionPercent, productModels, satisfaction)
    }
}

/** One thing customers rate («تطابق با عکس»). Values from the contract's SatisfactionSummary.metrics[].key. */
enum class SatisfactionMetricKey {
    PHOTO_MATCH,
    QUALITY,
    SHIPPING_SPEED,
    PACKAGING,
    SUPPORT,
}

/** How satisfied customers are with one [key], in percent. Its label is the HTTP layer's, from [key]. */
data class SatisfactionMetric(val key: SatisfactionMetricKey, val percent: Int) {
    init {
        require(percent in SiteStats.PERCENT) { "Invalid percent $percent of $key" }
    }
}

/**
 * The summary of what customers said (the contract's SatisfactionSummary): an average out of 5 with one decimal, kept
 * as tenths so no Double ever holds it (4.9 is 49), how many ratings it comes from, the metrics (each key once) and the
 * channels it was gathered on (each once). The scale is always 5.
 */
@ConsistentCopyVisibility
data class Satisfaction private constructor(
    val averageRatingTenths: Int,
    val totalCount: Int,
    val metrics: List<SatisfactionMetric>,
    val sources: List<SocialChannel>,
) {
    init {
        require(averageRatingTenths in RATING_TENTHS && totalCount in SiteStats.COUNT) { "Invalid Satisfaction $averageRatingTenths/$totalCount" }
        require(metrics.map { it.key }.toSet().size == metrics.size && sources.toSet().size == sources.size) { "A metric or a source twice" }
    }

    /** One metric as the panel sent it. */
    data class MetricInput(val key: String?, val percent: Int?)

    companion object {
        const val SCALE = 5
        val RATING_TENTHS = 0..SCALE * 10

        /**
         * @param averageRating the number as the client wrote it (`4.9`): 0 to 5 with at most one decimal, else
         *   RATING_INVALID on `averageRating`.
         */
        fun makeNew(averageRating: String?, totalCount: Int?, metrics: List<MetricInput>?, sources: List<String?>?): Result<Satisfaction, InputProblems> {
            val problems = Problems()
            val rating = problems.take(ratingTenthsOf(averageRating).forField("averageRating"))
            val count = problems.take(totalCount.requiredIn(SiteStats.COUNT).forField("totalCount"))
            val checkedMetrics = problems.take(metricsOf(metrics))
            val checkedSources = problems.take(sourcesOf(sources))
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(Satisfaction(rating.value, count.value, checkedMetrics.value, checkedSources.value))
        }

        private fun ratingTenthsOf(raw: String?): Result<Int, ResultFailure> {
            if (raw == null) return Result.failure(FieldRequired)
            val number = raw.trim().toBigDecimalOrNull() ?: return Result.failure(Problem.RatingInvalid)
            if (number.stripTrailingZeros().scale() > 1 || number < BigDecimal.ZERO || number > BigDecimal(SCALE)) return Result.failure(Problem.RatingInvalid)
            return Result.success(number.movePointRight(1).intValueExact())
        }

        private fun metricsOf(raw: List<MetricInput>?): Result<List<SatisfactionMetric>, ResultFailure> {
            if (raw == null) return Result.failure(FieldRequired.under("metrics"))
            val seen = mutableSetOf<SatisfactionMetricKey>()
            val problems = mutableListOf<ResultFailure>()
            val metrics = raw.mapIndexedNotNull { index, metric ->
                val key = SatisfactionMetricKey.entries.firstOrNull { it.name == metric.key }
                val percent = metric.percent.requiredIn(SiteStats.PERCENT).forField("percent")
                val twice = key != null && !seen.add(key)
                val itemProblems = listOfNotNull(
                    if (key == null) Problem.MetricInvalid else null,
                    percent.failureOrNull(),
                    if (twice) ListItemDuplicate.under("key") else null,
                )
                if (itemProblems.isNotEmpty()) problems += itemProblems.under("$index")
                val value = percent.getOrNull()
                if (key != null && value != null && !twice) SatisfactionMetric(key, value) else null
            }
            return if (problems.isEmpty()) Result.success(metrics) else Result.failure(problems.under("metrics"))
        }

        private fun sourcesOf(raw: List<String?>?): Result<List<SocialChannel>, ResultFailure> {
            if (raw == null) return Result.failure(FieldRequired.under("sources"))
            val problems = mutableListOf<ResultFailure>()
            val sources = raw.mapIndexedNotNull { index, name ->
                val channel = SocialChannel.entries.firstOrNull { it.name == name }
                when {
                    channel == null -> problems += Problem.SourceInvalid.under("$index")
                    raw.indexOf(name) != index -> problems += ListItemDuplicate.under("$index")
                }
                channel
            }
            return if (problems.isEmpty()) Result.success(sources) else Result.failure(problems.under("sources"))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(averageRatingTenths: Int, totalCount: Int, metrics: List<SatisfactionMetric>, sources: List<SocialChannel>): Satisfaction =
            Satisfaction(averageRatingTenths, totalCount, metrics, sources)
    }

    sealed class Problem(code: String, field: String? = null) : ResultFailure(code, FailureKind.INVALID, field) {
        data object RatingInvalid : Problem("RATING_INVALID")

        data object MetricInvalid : Problem("SATISFACTION_METRIC_INVALID", "key")

        data object SourceInvalid : Problem("SOCIAL_CHANNEL_INVALID")
    }
}
