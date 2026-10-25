package com.kiva.application.usecase.tracking

import com.kiva.common.valueobject.IranianMobileNumber

/**
 * What the public daily list may say of a recipient: never a whole name or number. Masking happens here, in the
 * application, so no path can hand the HTTP layer the full values (`GET /tracking/daily` is public).
 */
object DailyShipmentMask {
    private const val HIDDEN = "***"

    /** Each word's first letter and `***`: «سارا محمدی» → «س*** م***». */
    fun name(fullName: String): String = words(fullName).joinToString(" ") { it.take(1) + HIDDEN }

    /** The name's first letter: «س». */
    fun initial(fullName: String): String = words(fullName).firstOrNull()?.take(1).orEmpty()

    /** The number's last four digits: «4821». */
    fun phoneLast4(phone: IranianMobileNumber): String = phone.value.takeLast(4)

    /** «09** *** 4821». */
    fun phone(phone: IranianMobileNumber): String = "09** *** ${phoneLast4(phone)}"

    private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
