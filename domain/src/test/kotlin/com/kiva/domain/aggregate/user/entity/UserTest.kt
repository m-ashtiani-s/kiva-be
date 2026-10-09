package com.kiva.domain.aggregate.user.entity

import com.kiva.common.patch.Patch
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.model.ProfilePatch
import com.kiva.domain.aggregate.user.model.valueobject.Gender
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.domain.aggregate.user.model.valueobject.Role.ADMIN
import com.kiva.domain.aggregate.user.model.valueobject.Role.CUSTOMER
import com.kiva.domain.aggregate.user.model.valueobject.Role.OPERATOR
import com.kiva.domain.aggregate.user.model.valueobject.Role.SUPPORT
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class UserTest {

    private val now: Instant = Instant.parse("2026-10-09T08:30:00Z")
    private val today: LocalDate = LocalDate.of(2026, 10, 9)
    private val phone = IranianMobileNumber.constructFromPersisted("09121234567")
    private val admin = UserId.constructFromPersisted(1)

    private fun newUser(id: Long = 1024): User = User.register(UserId.constructFromPersisted(id), phone, now)

    private fun User.updated(patch: ProfilePatch): User =
        updateProfile(patch, today).getOrNull() ?: fail("expected the profile to change: ${updateProfile(patch, today)}")

    /** The field problems of a refused profile, as `field to code`. */
    private fun User.problemsOf(patch: ProfilePatch): List<Pair<String?, String>> {
        val failure = updateProfile(patch, today).failureOrNull() ?: fail("expected the profile to be refused")
        assertEquals("VALIDATION_ERROR", failure.code)
        assertEquals(FailureKind.INVALID, failure.kind)
        return failure.causes.map { it.field to it.code }
    }

    @Nested
    inner class Registering {
        @Test
        fun `a new account is a customer with an empty profile and no marketing SMS`() {
            val user = newUser()

            assertEquals(1024L, user.id.value)
            assertEquals(phone, user.phone)
            assertEquals(setOf(CUSTOMER), user.roles.values)
            assertFalse(user.marketingSmsOptIn)
            assertEquals(Gender.UNSPECIFIED, user.gender)
            assertNull(user.firstName)
            assertNull(user.lastName)
            assertNull(user.email)
            assertNull(user.birthDate)
            assertNull(user.defaultMessenger)
            assertNull(user.defaultMessengerPhone)
            assertEquals(now, user.createdOn)
            assertEquals(0L, user.version)
            assertFalse(user.isStaff)
        }

        @Test
        fun `the system never logs in, so it can't be registered`() {
            assertFailsWith<IllegalArgumentException> { User.register(UserId.SYSTEM, phone, now) }
        }
    }

    @Nested
    inner class UpdatingTheProfile {
        private val sara = newUser().updated(
            ProfilePatch(firstName = Patch.Set("سارا"), lastName = Patch.Set("محمدی"), email = Patch.Set("sara@x.com")),
        )

        @Test
        fun `an empty patch changes nothing`() {
            val same = sara.updated(ProfilePatch())

            assertEquals("سارا", same.firstName?.value)
            assertEquals("محمدی", same.lastName?.value)
            assertEquals("sara@x.com", same.email?.value)
            assertEquals(Gender.UNSPECIFIED, same.gender)
            assertFalse(same.marketingSmsOptIn)
        }

        @Test
        fun `a sent value is set, normalized by its value object`() {
            val user = newUser().updated(
                ProfilePatch(
                    firstName = Patch.Set("  علي "),
                    email = Patch.Set("Sara@X.com"),
                    birthDate = Patch.Set(LocalDate.of(1996, 9, 10)),
                    gender = Patch.Set("FEMALE"),
                    marketingSmsOptIn = Patch.Set(true),
                ),
            )

            assertEquals("علی", user.firstName?.value)
            assertEquals("sara@x.com", user.email?.value)
            assertEquals(LocalDate.of(1996, 9, 10), user.birthDate?.value)
            assertEquals(Gender.FEMALE, user.gender)
            assertTrue(user.marketingSmsOptIn)
        }

        @Test
        fun `a sent null clears that field and only that field`() {
            val user = sara.updated(ProfilePatch(firstName = Patch.Set(null)))

            assertNull(user.firstName)
            assertEquals("محمدی", user.lastName?.value)
            assertEquals("sara@x.com", user.email?.value)
        }

        @Test
        fun `an empty string is refused, not taken as clearing`() {
            assertEquals(listOf("firstName" to "PERSON_NAME_REQUIRED"), sara.problemsOf(ProfilePatch(firstName = Patch.Set(""))))
        }

        @Test
        fun `two wrong fields are two problems in one VALIDATION_ERROR, each under its field`() {
            val problems = sara.problemsOf(
                ProfilePatch(firstName = Patch.Set("<b>"), email = Patch.Set("sara"), lastName = Patch.Set("رضایی")),
            )

            assertEquals(listOf("firstName" to "PERSON_NAME_INVALID", "email" to "EMAIL_INVALID"), problems)
        }

        @Test
        fun `every wrong field is reported at once`() {
            val problems = sara.problemsOf(
                ProfilePatch(
                    firstName = Patch.Set(""),
                    lastName = Patch.Set("ن".repeat(51)),
                    email = Patch.Set("sara@"),
                    birthDate = Patch.Set(today.plusDays(1)),
                    gender = Patch.Set("female"),
                    defaultMessenger = Patch.Set("WHATSAPP"),
                    defaultMessengerPhone = Patch.Set("0912"),
                ),
            )

            assertEquals(
                listOf(
                    "firstName" to "PERSON_NAME_REQUIRED",
                    "lastName" to "PERSON_NAME_TOO_LONG",
                    "email" to "EMAIL_INVALID",
                    "birthDate" to "BIRTH_DATE_INVALID",
                    "defaultMessenger" to "MESSENGER_CHANNEL_INVALID",
                    "defaultMessengerPhone" to "PHONE_INVALID",
                    "gender" to "GENDER_INVALID",
                ),
                problems,
            )
        }

        @Test
        fun `a refused patch changes nothing on the user it was tried on`() {
            sara.updateProfile(ProfilePatch(firstName = Patch.Set("مریم"), email = Patch.Set("bad")), today)

            assertEquals("سارا", sara.firstName?.value)
        }

        @Test
        fun `the birth date is checked against the today it is given`() {
            assertEquals(today, newUser().updated(ProfilePatch(birthDate = Patch.Set(today))).birthDate?.value)
            assertEquals(listOf("birthDate" to "BIRTH_DATE_INVALID"), newUser().problemsOf(ProfilePatch(birthDate = Patch.Set(today.plusDays(1)))))
        }

        @Test
        fun `the identity, phone, roles and version are not part of the profile`() {
            val user = sara.updated(ProfilePatch(firstName = Patch.Set("مریم")))

            assertEquals(sara.id, user.id)
            assertEquals(sara.phone, user.phone)
            assertEquals(sara.roles, user.roles)
            assertEquals(sara.createdOn, user.createdOn)
            assertEquals(sara.version, user.version)
        }
    }

    @Nested
    inner class Messenger {
        private val withTelegram = newUser().updated(
            ProfilePatch(defaultMessenger = Patch.Set("TELEGRAM"), defaultMessengerPhone = Patch.Set("09351112233")),
        )

        @Test
        fun `a messenger with its number is kept`() {
            assertEquals(MessengerChannel.TELEGRAM, withTelegram.defaultMessenger)
            assertEquals("09351112233", withTelegram.defaultMessengerPhone?.value)
        }

        @Test
        fun `a messenger without a number is fine`() {
            assertEquals(MessengerChannel.BALE, newUser().updated(ProfilePatch(defaultMessenger = Patch.Set("BALE"))).defaultMessenger)
        }

        @Test
        fun `a messenger number without a messenger is MESSENGER_REQUIRED`() {
            val problems = newUser().problemsOf(ProfilePatch(defaultMessengerPhone = Patch.Set("09351112233")))

            assertEquals(listOf("defaultMessenger" to "MESSENGER_REQUIRED"), problems)
        }

        @Test
        fun `clearing the messenger while its number stays is MESSENGER_REQUIRED too`() {
            assertEquals(
                listOf("defaultMessenger" to "MESSENGER_REQUIRED"),
                withTelegram.problemsOf(ProfilePatch(defaultMessenger = Patch.Set(null))),
            )
        }

        @Test
        fun `clearing both together is fine`() {
            val user = withTelegram.updated(ProfilePatch(defaultMessenger = Patch.Set(null), defaultMessengerPhone = Patch.Set(null)))

            assertNull(user.defaultMessenger)
            assertNull(user.defaultMessengerPhone)
        }

        @Test
        fun `MESSENGER_REQUIRED comes together with the other problems of the form`() {
            val problems = newUser().problemsOf(
                ProfilePatch(email = Patch.Set("bad"), defaultMessengerPhone = Patch.Set("09351112233")),
            )

            assertEquals(listOf("email" to "EMAIL_INVALID", "defaultMessenger" to "MESSENGER_REQUIRED"), problems)
        }

        @Test
        fun `a messenger number is any way of writing an Iranian mobile`() {
            val user = newUser().updated(
                ProfilePatch(defaultMessenger = Patch.Set("RUBIKA"), defaultMessengerPhone = Patch.Set("+98 935 111 2233")),
            )

            assertEquals("09351112233", user.defaultMessengerPhone?.value)
        }
    }

    @Nested
    inner class PanelRoles {
        @Test
        fun `an admin gives a user a panel role, and CUSTOMER stays`() {
            val user = newUser().setPanelRoles(listOf(OPERATOR), by = admin).getOrNull() ?: fail("expected the roles to change")

            assertEquals(setOf(CUSTOMER, OPERATOR), user.roles.values)
            assertTrue(user.isStaff)
        }

        @Test
        fun `a list without CUSTOMER still keeps CUSTOMER, and an empty list takes every panel role away`() {
            val operator = newUser().setPanelRoles(listOf(OPERATOR, ADMIN), by = admin).getOrNull() ?: fail()
            val customer = operator.setPanelRoles(emptyList(), by = admin).getOrNull() ?: fail()

            assertEquals(setOf(CUSTOMER, ADMIN, OPERATOR), operator.roles.values)
            assertEquals(Roles.CUSTOMER_ONLY, customer.roles)
            assertFalse(customer.isStaff)
        }

        @Test
        fun `nobody changes their own roles`() {
            val self = newUser(id = 1)

            val result = self.setPanelRoles(listOf(ADMIN, OPERATOR), by = self.id)

            assertEquals(Result.failure(User.SetPanelRolesFailure.OwnRoles), result)
            assertEquals("ROLE_CHANGE_NOT_ALLOWED", User.SetPanelRolesFailure.OwnRoles.code)
            assertEquals(FailureKind.BUSINESS_RULE, User.SetPanelRolesFailure.OwnRoles.kind)
        }

        @Test
        fun `changing roles leaves the profile as it was`() {
            val sara = newUser().updated(ProfilePatch(firstName = Patch.Set("سارا")))

            val support = sara.setPanelRoles(listOf(SUPPORT), by = admin).getOrNull() ?: fail()

            assertEquals("سارا", support.firstName?.value)
            assertEquals(sara.version, support.version)
        }
    }

    @Test
    fun `toString shows the id and no personal data`() {
        val user = newUser().updated(ProfilePatch(firstName = Patch.Set("سارا")))

        assertEquals("User(id=1024)", user.toString())
    }

    @Test
    fun `two users are the same user when their ids are`() {
        assertEquals(newUser(), newUser().updated(ProfilePatch(firstName = Patch.Set("سارا"))))
    }

    @Test
    fun `MESSENGER_REQUIRED names the field the client should fix`() {
        val failure: ResultFailure = User.MessengerRequired
        assertEquals("defaultMessenger", failure.field)
        assertEquals(FailureKind.INVALID, failure.kind)
    }
}
