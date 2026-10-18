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
            AuditAction.ORDER_NOTE_ADDED -> "یادداشت داخلی روی سفارش $name نوشته شد"
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
