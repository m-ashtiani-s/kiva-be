package com.kiva.application.usecase.tracking.usecase.query

import com.kiva.application.dto.GuestTrackingDto
import com.kiva.application.dto.OrderSummaryDto
import com.kiva.application.dto.PreShipmentMediaDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.order.OrderDetails
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * A guest follows an order without logging in (`POST /tracking/lookup`, public, 10 per 10 minutes per address): its
 * code and the buyer's or the recipient's phone. Only a paid order. A code that can't be one, an order that doesn't
 * exist or wasn't paid, and another phone are all the same TRACKING_NOT_FOUND, so nothing can be guessed from the
 * answer; only a phone that can't be one is 400. The code may come as digits only (`218340`), in Persian digits too.
 *
 * Nothing personal in the log: the code (once it is one), never the phone.
 */
class LookupGuestTrackingUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val orderDetails: OrderDetails,
    private val userRepository: UserRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<LookupGuestTrackingUc.Qry, GuestTrackingDto, LookupGuestTrackingUc.Failure> {

    private val logger = getLogger()

    /** The body as it came; [phone] is never logged ([toString]). */
    data class Qry(val orderCode: String?, val phone: String?) {
        override fun toString(): String = "Qry(orderCode=$orderCode)"
    }

    override suspend fun execute(query: Qry): Result<GuestTrackingDto, Failure> {
        val phone = when (val parsed = IranianMobileNumber.makeNew(query.phone.orEmpty())) {
            is Result.Success -> parsed.value
            is Result.Failure -> return Result.failure(Failure.InvalidInput(listOf(parsed.failure)))
        }
        val code = codeOf(query.orderCode)
        logger.info("LookupGuestTrackingUc.execute orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val order = orderQmRepository.findByCode(code)?.takeIf { it.status.isPaid } ?: return@inTransaction Result.failure(Failure.NotFound)
            val buyer = userRepository.findById(order.userId)?.phone
            if (phone != order.address.recipientPhone && phone != buyer) return@inTransaction Result.failure(Failure.NotFound)

            val media = orderDetails.mediaOf(listOf(order))
            val summary = OrderSummaryDto.of(order, shopSettingsRepository.get(), clock.instant(), media = media)
            Result.success(GuestTrackingDto(summary, order.messenger.channel, PreShipmentMediaDto.of(order, media).sent.map { it.media }))
        }
    }

    /** `KV-218340`, `kv-۲۱۸۳۴۰` or just `218340`; null when it can't be a code. */
    private fun codeOf(raw: String?): OrderCode? {
        val text = raw?.trim()?.normalizeDigits() ?: return null
        val withPrefix = if (text.isNotEmpty() && text.all(Char::isDigit)) "KV-$text" else text
        return OrderCode.makeNew(withPrefix).getOrNull()
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** Not found, or not this phone's: the same answer either way. */
        data object NotFound : Failure("TRACKING_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
