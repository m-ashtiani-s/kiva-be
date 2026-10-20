package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AuditLogEntryDto
import com.kiva.application.port.AuditChange
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.infrastructure.common.http.toPersianNumber
import com.kiva.infrastructure.common.text.JalaliDates
import java.time.Instant

/**
 * The Persian sentence of a change log row (`summary`), written when the log is read, never stored: the domain and the
 * use cases only give an action, a label and the changed fields (roadmap section 1, row 16). A sentence fixed here is
 * fixed for every row already stored. Every [AuditAction] has one (AuditTextsTest).
 */
object AuditTexts {

    fun summary(entry: AuditLogEntryDto): String {
        val name = "«${entry.entityLabel ?: entry.entityId}»"
        val changes = entry.changes.orEmpty()
        return when (entry.action) {
            AuditAction.USER_ROLES_CHANGED -> rolesChanged(name, changes)
            AuditAction.CATEGORY_CREATED -> "دسته‌ی $name ساخته شد"
            AuditAction.CATEGORY_UPDATED -> "دسته‌ی $name ویرایش شد"
            AuditAction.CATEGORY_DELETED -> "دسته‌ی $name حذف شد"
            AuditAction.COLOR_CREATED -> "رنگ $name ساخته شد"
            AuditAction.COLOR_UPDATED -> "رنگ $name ویرایش شد"
            AuditAction.COLOR_DELETED -> "رنگ $name حذف شد"
            AuditAction.MATERIAL_CREATED -> "متریال $name ساخته شد"
            AuditAction.MATERIAL_UPDATED -> "متریال $name ویرایش شد"
            AuditAction.MATERIAL_DELETED -> "متریال $name حذف شد"
            AuditAction.PRODUCT_CREATED -> "محصول $name ساخته شد"
            AuditAction.PRODUCT_PRICE_CHANGED -> priceChanged(changes) ?: "قیمت محصول $name تغییر کرد"
            AuditAction.PRODUCT_UPDATED -> "محصول $name ویرایش شد"
            AuditAction.PRODUCT_PUBLISHED -> "محصول $name منتشر شد"
            AuditAction.PRODUCT_UNPUBLISHED -> "محصول $name از انتشار دراومد"
            AuditAction.PRODUCT_ARCHIVED -> "محصول $name آرشیو شد"
            AuditAction.PRODUCT_PUBLISH_SCHEDULED -> scheduled(name, changes)
            AuditAction.PRODUCT_PUBLISH_UNSCHEDULED -> "برنامه‌ی انتشار محصول $name لغو شد"
            AuditAction.VARIANT_ADDED -> "رنگ $name به محصول اضافه شد"
            AuditAction.VARIANT_UPDATED -> "رنگ $name ویرایش شد"
            AuditAction.VARIANT_DEACTIVATED -> "رنگ $name غیرفعال شد"
            AuditAction.INVENTORY_ADJUSTED -> stockAdjusted(name, changes)
            AuditAction.ORDER_STATUS_CHANGED -> statusChanged(name, changes)
            AuditAction.ORDER_NOTE_ADDED -> "یادداشت داخلی روی سفارش $name نوشته شد"
            AuditAction.ORDER_MEDIA_SET -> "عکس‌های قبل از ارسال سفارش $name انتخاب شد"
            AuditAction.ORDER_PHOTO_SENT -> "ارسال عکس‌های سفارش $name برای مشتری ثبت شد"
            AuditAction.ORDER_SHIPPED ->
                if (changes.any { it.field == "trackingCode" && it.from != null }) "کد رهگیری سفارش $name اصلاح شد" else "سفارش $name ارسال شد"
            AuditAction.REFUND_CREATED -> refundCreated(name, changes)
            AuditAction.REFUND_UPDATED -> refundUpdated(name, changes)
            AuditAction.ORDER_CHANGE_RESOLVED -> changeResolved(name, changes)
            AuditAction.SETTINGS_UPDATED -> "تنظیمات فروشگاه ویرایش شد (${changes.size.toLong().toPersianNumber()} مورد)"
        }
    }

    /** The decision the change log kept: carried out (with what was changed) or turned down. */
    private fun changeResolved(name: String, changes: List<AuditChange>): String {
        val type = changes.firstOrNull { it.field == "changeType" }?.to
        return when (changes.firstOrNull { it.field == "decision" }?.to) {
            "REJECT" -> "درخواست تغییر سفارش $name رد شد"
            "APPLY" -> when (type) {
                "COLOR" -> "رنگ کالای سفارش $name عوض شد"
                "MODEL" -> "مدل کالای سفارش $name عوض شد"
                "CANCEL_ITEM" -> "یه قلم از سفارش $name حذف شد"
                else -> "درخواست تغییر سفارش $name انجام شد"
            }
            else -> "به درخواست تغییر سفارش $name رسیدگی شد"
        }
    }

    private fun rolesChanged(name: String, changes: List<AuditChange>): String {
        val roles = changes.firstOrNull { it.field == "roles" }?.to ?: return "نقش‌های $name تغییر کرد"
        val names = ROLE_NAMES.filterKeys { "\"$it\"" in roles }.values
        return "نقش‌های $name شد: ${names.joinToString("، ")}"
    }

    /** «قیمت از ۱٬۸۹۰٬۰۰۰ به ۱٬۷۹۰٬۰۰۰ تومان تغییر کرد», or null when the price itself didn't change. */
    private fun priceChanged(changes: List<AuditChange>): String? {
        val price = changes.firstOrNull { it.field == "price" } ?: return null
        val from = price.from?.toLongOrNull() ?: return null
        val to = price.to?.toLongOrNull() ?: return null
        return "قیمت از ${from.toPersianNumber()} به ${to.toPersianNumber()} تومان تغییر کرد"
    }

    private fun scheduled(name: String, changes: List<AuditChange>): String {
        val at = changes.firstOrNull { it.field == "publishAt" }?.to?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return "انتشار محصول $name برنامه‌ریزی شد"
        return "انتشار محصول $name برای ${JalaliDates.weekdayDayAndMonth(ShopCalendar.dateOf(at))} برنامه‌ریزی شد"
    }

    private fun statusChanged(name: String, changes: List<AuditChange>): String = when (changes.firstOrNull { it.field == "status" }?.to) {
        "CANCELLED" -> "سفارش $name لغو شد"
        "DELIVERED" -> "سفارش $name تحویل شد"
        else -> "وضعیت سفارش $name تغییر کرد"
    }

    private fun refundCreated(name: String, changes: List<AuditChange>): String {
        val amount = changes.firstOrNull { it.field == "amount" }?.to?.toLongOrNull() ?: return "استرداد برای سفارش $name ثبت شد"
        return "استرداد ${amount.toPersianNumber()} تومان برای سفارش $name ثبت شد"
    }

    private fun refundUpdated(name: String, changes: List<AuditChange>): String = when (changes.firstOrNull { it.field == "status" }?.to) {
        "PROCESSING" -> "استرداد سفارش $name در حال انجامه"
        "COMPLETED" -> "استرداد سفارش $name انجام شد"
        "FAILED" -> "استرداد سفارش $name ناموفق بود"
        else -> "استرداد سفارش $name تغییر کرد"
    }

    private fun stockAdjusted(name: String, changes: List<AuditChange>): String {
        val stock = changes.firstOrNull { it.field == "stockOnHand" }
        val from = stock?.from?.toLongOrNull()
        val to = stock?.to?.toLongOrNull()
        if (from == null || to == null) return "موجودی $name تغییر کرد"
        return "موجودی $name از ${from.toPersianNumber()} به ${to.toPersianNumber()} رسید"
    }

    private val ROLE_NAMES = linkedMapOf(
        "ADMIN" to "مدیر",
        "CATALOG_MANAGER" to "مدیر کاتالوگ",
        "OPERATOR" to "اپراتور سفارش",
        "CONTENT_EDITOR" to "ویراستار محتوا",
        "SUPPORT" to "پشتیبانی",
        "CUSTOMER" to "مشتری",
    )
}
