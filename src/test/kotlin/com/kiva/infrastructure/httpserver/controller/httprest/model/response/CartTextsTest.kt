package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CartIssueDto
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.CartIssueCode
import com.kiva.domain.aggregate.order.model.valueobject.FreeShippingProgress
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.common.http.KivaJson
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CartTextsTest {

    private fun issue(code: CartIssueCode, current: Long? = null) =
        CartIssueDto(code, "ci_0000000001", "کیف دستی آیدا", "کاراملی", 3, current)

    @Test
    fun `adding and removing tell the bag, its color and how many`() {
        assertEquals("کراس‌بادی نیلا — زیتونی (۱ عدد) به سبد اضافه شد", CartTexts.added("کراس‌بادی نیلا", "زیتونی", 1))
        assertNull(CartTexts.added("کراس‌بادی نیلا", "زیتونی", 0))
        assertEquals("کراس‌بادی نیلا — زیتونی از سبد حذف شد", CartTexts.removed("کراس‌بادی نیلا", "زیتونی"))
    }

    @Test
    fun `every issue has its own sentence, naming the bag and its color`() {
        assertEquals("قیمت «کیف دستی آیدا — کاراملی» از آخرین بازدیدت بیشتر شده.", CartTexts.issue(issue(CartIssueCode.PRICE_INCREASED), "پست معمولی"))
        assertEquals("تعداد «کیف دستی آیدا — کاراملی» به ۲ عدد کم شد؛ بیشتر از این نمی‌شه خرید.", CartTexts.issue(issue(CartIssueCode.QUANTITY_REDUCED, 2), "پست معمولی"))
        assertEquals(
            "«کیف دستی آیدا — کاراملی» از سبدت برداشته شد، چون سقف خرید این محصول با رنگ‌های دیگه‌ش پر شده.",
            CartTexts.issue(issue(CartIssueCode.QUANTITY_REDUCED, 0), "پست معمولی"),
        )
        assertEquals(
            "روش ارسالی که انتخاب کرده بودی دیگه در دسترس نیست؛ «پست معمولی» انتخاب شد.",
            CartTexts.issue(CartIssueDto(CartIssueCode.SHIPPING_METHOD_CHANGED, null, null, null, null, null), "پست معمولی"),
        )
        assertEquals(
            "کد تخفیف «KIVA10» دیگه روی سبدت اعمال نمی‌شه و برداشته شد.",
            CartTexts.issue(CartIssueDto(CartIssueCode.DISCOUNT_REMOVED, null, null, null, null, null, "KIVA10"), "پست معمولی"),
        )
        CartIssueCode.entries.forEach { code -> assert(CartTexts.issue(issue(code, 1), "پست معمولی").isNotBlank()) }
    }

    @Test
    fun `delivery is told from the days themselves`() {
        assertEquals("تحویل ۳ تا ۵ روز کاری", CartTexts.delivery(DeliveryDays.constructFromPersisted(3, 5)))
        assertEquals("تحویل ۲ روز کاری", CartTexts.delivery(DeliveryDays.constructFromPersisted(2, 2)))
    }

    @Test
    fun `the free-shipping bar says how much is left, or that it is free`() {
        val short = FreeShippingProgress(Money.constructFromPersisted(3_000_000), ShippingMethodCode.POST, false, Money.constructFromPersisted(1_550_000), 483)
        val free = FreeShippingProgress(Money.constructFromPersisted(3_000_000), ShippingMethodCode.POST, true, Money.ZERO, 1000)

        assertEquals("فقط ۱٬۵۵۰٬۰۰۰ تومان تا ارسال رایگان با پست معمولی", CartTexts.freeShipping(short, "پست معمولی"))
        assertEquals("ارسال با پست معمولی رایگان شد", CartTexts.freeShipping(free, "پست معمولی"))
    }

    @Serializable
    private data class Percent(@Serializable(with = PerMilleAsPercentSerializer::class) val progressPercent: Int)

    @Test
    fun `progressPercent is written with one decimal, digit for digit`() {
        assertEquals("""{"progressPercent":48.3}""", KivaJson.encodeToString(Percent.serializer(), Percent(483)))
        assertEquals("""{"progressPercent":100.0}""", KivaJson.encodeToString(Percent.serializer(), Percent(1000)))
        assertEquals("""{"progressPercent":0.0}""", KivaJson.encodeToString(Percent.serializer(), Percent(0)))
    }
}
