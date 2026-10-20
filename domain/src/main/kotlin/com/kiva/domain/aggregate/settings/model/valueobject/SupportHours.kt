package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.text.normalizePersianLetters
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * One line of the shop's support hours («شنبه تا چهارشنبه، ۹ تا ۲۱»), the contract's SupportHours: its [label], the
 * [weekdays] it is about (at least one, each once, in the order given), and [from] to [to] in Tehran time, or both null
 * for «closed». [messengerOnly]: on those days only the messengers answer («جمعه و تعطیلات — فقط پیام‌رسان»).
 */
@ConsistentCopyVisibility
data class SupportHours private constructor(
    val label: String,
    val weekdays: List<DayOfWeek>,
    val from: LocalTime?,
    val to: LocalTime?,
    val messengerOnly: Boolean,
) {
    init {
        require(isValid(label, weekdays, from, to)) { "Invalid SupportHours '$label' $weekdays $from..$to" }
    }

    companion object {
        const val MAX_LABEL_LENGTH = 50
        private val TIME = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

        private fun isValid(label: String, weekdays: List<DayOfWeek>, from: LocalTime?, to: LocalTime?): Boolean =
            label.isNotBlank() && label.length <= MAX_LABEL_LENGTH && weekdays.isNotEmpty() && weekdays.toSet().size == weekdays.size &&
                (from == null) == (to == null) && (from == null || from < to)

        /**
         * The panel's line as it came. Anything wrong with it is one answer, SUPPORT_HOURS_INVALID, which the caller puts
         * under the line's index: the form shows the line, not its parts.
         *
         * @param weekdays the contract's names (`SATURDAY`).
         * @param from and [to]: `HH:mm`.
         */
        fun makeNew(label: String?, weekdays: List<String>?, from: String?, to: String?, messengerOnly: Boolean?): Result<SupportHours, Failure> {
            val text = label?.trim()?.normalizePersianLetters() ?: return Result.failure(Failure.Invalid)
            val days = weekdays?.map { name -> DayOfWeek.entries.firstOrNull { it.name == name } ?: return Result.failure(Failure.Invalid) }
                ?: return Result.failure(Failure.Invalid)
            val start = from?.let { timeOf(it) ?: return Result.failure(Failure.Invalid) }
            val end = to?.let { timeOf(it) ?: return Result.failure(Failure.Invalid) }
            if (!isValid(text, days, start, end)) return Result.failure(Failure.Invalid)
            return Result.success(SupportHours(text, days, start, end, messengerOnly ?: false))
        }

        private fun timeOf(raw: String): LocalTime? {
            val text = raw.normalizeDigits().trim()
            return if (TIME.matches(text)) LocalTime.parse(text) else null
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(label: String, weekdays: List<DayOfWeek>, from: LocalTime?, to: LocalTime?, messengerOnly: Boolean): SupportHours =
            SupportHours(label, weekdays, from, to, messengerOnly)
    }

    // No field here: the caller puts it under the line (`supportHours.1`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("SUPPORT_HOURS_INVALID")
    }
}
