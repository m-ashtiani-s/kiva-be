package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.AddressTitle
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.OrderFixtures.saraPhone
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OrderValueObjectsTest {

    @Nested
    inner class Notes {
        @Test
        fun `a customer note - blank is none, 300 is fine, 301 is too long, and Arabic letters become Persian`() {
            assertNull(CustomerNote.makeNew("   ").getOrNull())
            assertNull(CustomerNote.makeNew(null).getOrNull())
            assertEquals(300, CustomerNote.makeNew("ا".repeat(300)).getOrNull()?.value?.length)
            assertEquals(CustomerNote.Failure.TooLong, CustomerNote.makeNew("ا".repeat(301)).failureOrNull())
            assertEquals("customerNote", CustomerNote.Failure.TooLong.field)
            assertEquals("لطفاً زنگ نزنید", CustomerNote.makeNew("  لطفاً زنگ نزنيد ").getOrNull()?.value)
        }

        @Test
        fun `a line break becomes a space, another control character is refused, and a heart typed as less-than 3 is fine`() {
            assertEquals("سلام خوبی", CustomerNote.makeNew("سلام\nخوبی").getOrNull()?.value)
            assertEquals(CustomerNote.Failure.InvalidCharacters, CustomerNote.makeNew("سلام\u0007").failureOrNull())
            assertEquals("دوستت دارم <3", GiftNote.makeNew("دوستت دارم <3", hidePrices = true).getOrNull()?.message)
        }

        @Test
        fun `a gift message is optional, at most 200, and the parcel hides prices or not`() {
            val noMessage = GiftNote.makeNew("", hidePrices = false).getOrNull()

            assertNull(noMessage?.message)
            assertFalse(noMessage!!.hidePrices)
            assertEquals(GiftNote.Failure.MessageTooLong, GiftNote.makeNew("ا".repeat(201), hidePrices = true).failureOrNull())
            assertEquals("message", GiftNote.Failure.MessageTooLong.field)
        }

        @Test
        fun `notes and messages never print`() {
            val printed = "${CustomerNote.constructFromPersisted("پشت در بذارید")} ${GiftNote.constructFromPersisted("تولدت مبارک", true)}"

            assertFalse("پشت در" in printed)
            assertFalse("تولدت" in printed)
        }

        @Test
        fun `a stored note that breaks the rules is a bug`() {
            assertFailsWith<IllegalArgumentException> { CustomerNote.constructFromPersisted(" ") }
            assertFailsWith<IllegalArgumentException> { GiftNote.constructFromPersisted("ا".repeat(201), true) }
        }
    }

    @Nested
    inner class Messenger {
        @Test
        fun `both texts wrong come back together, each on its own field`() {
            val refused = PreShipmentMessenger.makeNew(MessengerChannel.BALE, saraPhone, "a".repeat(51), "ا".repeat(201)).failureOrNull()

            assertEquals(listOf("username" to "MESSENGER_USERNAME_TOO_LONG", "note" to "MESSENGER_NOTE_TOO_LONG"), refused?.problems?.map { it.field to it.code })
        }

        @Test
        fun `the handle and the note are optional and trimmed`() {
            val messenger = PreShipmentMessenger.makeNew(MessengerChannel.TELEGRAM, saraPhone, " @sara_m ", "  ").getOrNull()!!

            assertEquals("@sara_m", messenger.username)
            assertNull(messenger.note)
        }

        @Test
        fun `it prints only its channel`() {
            val printed = "${PreShipmentMessenger.constructFromPersisted(MessengerChannel.RUBIKA, saraPhone, "@sara_m", "از داخل کیف")}"

            assertEquals("PreShipmentMessenger(channel=RUBIKA)", printed)
        }
    }

    @Test
    fun `a saved address is copied whole, with the names of its province and city, and prints none of it`() {
        val saved = Address.constructFromPersisted(
            AddressId.constructFromPersisted(42), AddressTitle.constructFromPersisted("خانه"), ProvinceId.constructFromPersisted(8),
            CityId.constructFromPersisted(394), AddressLine.constructFromPersisted("خیابان ولیعصر، کوچه نسترن"), null, null,
            PostalCode.constructFromPersisted("1965843117"), RecipientName.constructFromPersisted("سارا محمدی"),
            IranianMobileNumber.constructFromPersisted("09121234567"), isSelfRecipient = true, location = null, isDefault = true,
            createdOn = now, updatedOn = now,
        )

        val snapshot = AddressSnapshot.of(saved, "تهران", "تهران")

        assertEquals(saved.id, snapshot.addressId)
        assertEquals(listOf(saved.line, saved.postalCode, saved.recipientName, saved.recipientPhone), listOf(snapshot.line, snapshot.postalCode, snapshot.recipientName, snapshot.recipientPhone))
        assertEquals("AddressSnapshot(addressId=42)", "$snapshot")
    }

    @Test
    fun `a color's snapshot needs its name and a hex color`() {
        assertFailsWith<IllegalArgumentException> { OrderItemSnapshot.Color(ColorKey.constructFromPersisted("caramel"), "کاراملی", "C68B59") }
        assertFailsWith<IllegalArgumentException> { OrderItemSnapshot.Color(ColorKey.constructFromPersisted("caramel"), " ", "#C68B59") }
    }
}
