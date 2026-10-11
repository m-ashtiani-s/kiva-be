package com.kiva.domain.aggregate.user.entity

import com.kiva.common.patch.applyTo
import com.kiva.common.patch.mapResult
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.zip
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.model.ProfilePatch
import com.kiva.domain.aggregate.user.model.valueobject.BirthDate
import com.kiva.domain.aggregate.user.model.valueobject.Email
import com.kiva.domain.aggregate.user.model.valueobject.Gender
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.domain.aggregate.user.model.valueobject.PersonName
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import java.time.Instant
import java.time.LocalDate

/**
 * A person with an account: the phone they log in with, their profile, and their roles.
 *
 * Stored in a plain table rather than as events: personal data must be erasable (deleting an account), and the
 * security history lives in its own log. Two edits at the same moment are caught with [version] (optimistic locking):
 * the repository saves only if the row still has the version this user was read with.
 *
 * Immutable: every change returns a new User, which the use case saves.
 *
 * Rules it keeps by itself:
 * - every user has [Role.CUSTOMER], which can't be taken away
 * - nobody changes their own roles
 * - a messenger number goes with a messenger
 *
 * The phone being unique across users is not its job: it can't see other users. A unique index has the last word.
 *
 * @property phone what the user logs in with. It changes only through the phone change flow (codes from both numbers).
 * @property email typed by the user and never verified, so never used to log in or to get an account back.
 * @property marketingSmsOptIn false until the user chooses it themselves.
 * @property version the version this user was read with; the repository raises it on every save.
 */
class User private constructor(
    val id: UserId,
    val phone: IranianMobileNumber,
    val firstName: PersonName?,
    val lastName: PersonName?,
    val email: Email?,
    val birthDate: BirthDate?,
    val gender: Gender,
    val defaultMessenger: MessengerChannel?,
    val defaultMessengerPhone: IranianMobileNumber?,
    val marketingSmsOptIn: Boolean,
    val roles: Roles,
    val createdOn: Instant,
    val version: Long,
) {
    /** Has a panel role. Picks the kind of session at login: staff sessions are shorter. */
    val isStaff: Boolean get() = roles.isStaff

    companion object {
        /**
         * The account made at the first login of a phone: a customer with an empty profile, no marketing SMS, gender not
         * chosen. It can't fail: the phone is already a valid number.
         *
         * [id] is handed out before (a database sequence), so [UserId.SYSTEM] here is a bug: the system never logs in.
         */
        fun register(id: UserId, phone: IranianMobileNumber, now: Instant): User {
            require(id != UserId.SYSTEM) { "UserId.SYSTEM can't be a user's account" }
            return User(
                id = id,
                phone = phone,
                firstName = null,
                lastName = null,
                email = null,
                birthDate = null,
                gender = Gender.UNSPECIFIED,
                defaultMessenger = null,
                defaultMessengerPhone = null,
                marketingSmsOptIn = false,
                roles = Roles.CUSTOMER_ONLY,
                createdOn = now,
                version = 0,
            )
        }

        fun constructFromPersisted(
            id: UserId,
            phone: IranianMobileNumber,
            firstName: PersonName?,
            lastName: PersonName?,
            email: Email?,
            birthDate: BirthDate?,
            gender: Gender,
            defaultMessenger: MessengerChannel?,
            defaultMessengerPhone: IranianMobileNumber?,
            marketingSmsOptIn: Boolean,
            roles: Roles,
            createdOn: Instant,
            version: Long,
        ): User = User(
            id, phone, firstName, lastName, email, birthDate, gender, defaultMessenger, defaultMessengerPhone,
            marketingSmsOptIn, roles, createdOn, version,
        )
    }

    // ============ profile ============

    /**
     * Applies the profile form: fields not sent keep their value, a sent null clears an optional field.
     *
     * Every problem comes back at once, each under its field, as one VALIDATION_ERROR: two wrong fields are two
     * problems in one answer, not one now and the other after the fix. The messenger rule is checked on the profile as
     * it would be after the change, so clearing the messenger while keeping its number is refused too.
     *
     * @param today the date in Tehran, for the birth date (see [BirthDate.makeNew]).
     */
    fun updateProfile(patch: ProfilePatch, today: LocalDate): Result<User, UpdateProfileFailure> =
        Result.zip(
            Result.zip(
                patch.firstName.mapResult { clearOr(it, PersonName::makeNew) }.forField("firstName"),
                patch.lastName.mapResult { clearOr(it, PersonName::makeNew) }.forField("lastName"),
                patch.email.mapResult { clearOr(it, Email::makeNew) },
                patch.birthDate.mapResult { raw -> clearOr(raw) { BirthDate.makeNew(it, today) } },
                ::InputProblems,
            ) { newFirstName, newLastName, newEmail, newBirthDate ->
                Personal(
                    newFirstName.applyTo(firstName),
                    newLastName.applyTo(lastName),
                    newEmail.applyTo(email),
                    newBirthDate.applyTo(birthDate),
                )
            },
            messengerAfter(patch),
            patch.gender.mapResult(Gender::makeNew),
            { problems -> UpdateProfileFailure.InvalidInput(problems.flattenGroups()) },
        ) { personal, messenger, newGender ->
            copy(
                firstName = personal.firstName,
                lastName = personal.lastName,
                email = personal.email,
                birthDate = personal.birthDate,
                gender = newGender.applyTo(gender),
                defaultMessenger = messenger.channel,
                defaultMessengerPhone = messenger.phone,
                marketingSmsOptIn = patch.marketingSmsOptIn.applyTo(marketingSmsOptIn),
            )
        }

    /** The messenger and its number as they would be after [patch]; a number without a messenger is MESSENGER_REQUIRED. */
    private fun messengerAfter(patch: ProfilePatch): Result<Messenger, InputProblems> =
        Result.zip(
            patch.defaultMessenger.mapResult { clearOr(it, MessengerChannel::makeNew) }.forField("defaultMessenger"),
            patch.defaultMessengerPhone.mapResult { clearOr(it, IranianMobileNumber::makeNew) }.forField("defaultMessengerPhone"),
            ::InputProblems,
        ) { newChannel, newPhone -> Messenger(newChannel.applyTo(defaultMessenger), newPhone.applyTo(defaultMessengerPhone)) }
            .bind { messenger ->
                if (messenger.phone != null && messenger.channel == null) {
                    Result.failure(InputProblems(listOf(MessengerRequired)))
                } else {
                    Result.success(messenger)
                }
            }

    private data class Personal(val firstName: PersonName?, val lastName: PersonName?, val email: Email?, val birthDate: BirthDate?)

    private data class Messenger(val channel: MessengerChannel?, val phone: IranianMobileNumber?)

    // ============ roles ============

    /**
     * Replaces the user's panel roles with [roles]. CUSTOMER stays, whether it is in the list or not.
     *
     * Nobody changes their own roles (ROLE_CHANGE_NOT_ALLOWED), so the last ADMIN can't lock themselves out by mistake.
     * Who may give roles at all (ADMIN only) is the endpoint's guard: this user can't see [by]'s roles. Ending this
     * user's sessions after the change is the use case's job.
     */
    fun setPanelRoles(roles: Collection<Role>, by: UserId): Result<User, SetPanelRolesFailure> {
        if (by == id) return Result.failure(SetPanelRolesFailure.OwnRoles)
        return Result.success(copy(roles = Roles.of(roles)))
    }

    // ============ phone ============

    /**
     * Moves the account to [newPhone]. Only the phone change flow calls this, after codes from both phones were verified
     * and the new phone was found free. The old phone is free from then on: logging in with it makes a new account.
     */
    fun changePhone(newPhone: IranianMobileNumber): Result<User, ChangePhoneFailure> {
        if (newPhone == phone) return Result.failure(ChangePhoneFailure.SameAsCurrent)
        return Result.success(User(id, newPhone, firstName, lastName, email, birthDate, gender, defaultMessenger, defaultMessengerPhone, marketingSmsOptIn, roles, createdOn, version))
    }

    private fun copy(
        firstName: PersonName? = this.firstName,
        lastName: PersonName? = this.lastName,
        email: Email? = this.email,
        birthDate: BirthDate? = this.birthDate,
        gender: Gender = this.gender,
        defaultMessenger: MessengerChannel? = this.defaultMessenger,
        defaultMessengerPhone: IranianMobileNumber? = this.defaultMessengerPhone,
        marketingSmsOptIn: Boolean = this.marketingSmsOptIn,
        roles: Roles = this.roles,
    ): User = User(
        id, phone, firstName, lastName, email, birthDate, gender, defaultMessenger, defaultMessengerPhone,
        marketingSmsOptIn, roles, createdOn, version,
    )

    override fun equals(other: Any?): Boolean = other is User && other.id == id

    override fun hashCode(): Int = id.hashCode()

    // Only the id: everything else is personal data and stays out of logs.
    override fun toString(): String = "User(id=${id.value})"

    // ============ failures ============

    sealed class UpdateProfileFailure(code: String, kind: FailureKind, causes: List<ResultFailure>) :
        ResultFailure(code, kind, causes = causes) {
        /** Every problem of the form, each under its field. */
        data class InvalidInput(val problems: List<ResultFailure>) :
            UpdateProfileFailure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    /** A messenger number without a messenger to send the photos through. One of the problems in [UpdateProfileFailure.InvalidInput]. */
    data object MessengerRequired : ResultFailure("MESSENGER_REQUIRED", FailureKind.INVALID, field = "defaultMessenger")

    sealed class SetPanelRolesFailure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OwnRoles : SetPanelRolesFailure("ROLE_CHANGE_NOT_ALLOWED", FailureKind.BUSINESS_RULE)
    }

    // Field and code from the contract's phone change endpoints.
    sealed class ChangePhoneFailure(code: String, kind: FailureKind, field: String? = null) : ResultFailure(code, kind, field) {
        data object SameAsCurrent : ChangePhoneFailure("PHONE_SAME_AS_CURRENT", FailureKind.INVALID, field = "newPhone")
    }
}

/** null stays null (the field is cleared); a value goes through [makeNew]. */
private inline fun <T : Any, R> clearOr(raw: T?, makeNew: (T) -> Result<R, ResultFailure>): Result<R?, ResultFailure> =
    if (raw == null) Result.success(null) else makeNew(raw)
