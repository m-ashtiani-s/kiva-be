package com.kiva.application.usecase.admin

import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.repository.UserRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals

/** The two rules of decision 14 in SetUserRolesUc, under the roles lock (step 4.3). */
class SetUserRolesLastAdminTest {

    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val transactions = MockTransactionManager()

    private fun user(id: Long, vararg roles: Role): User {
        val made = User.register(UserId.constructFromPersisted(id), IranianMobileNumber.constructFromPersisted("0990000${id.toString().padStart(4, '0')}"), now)
        return if (roles.isEmpty()) made else made.setPanelRoles(roles.toList(), UserId.SYSTEM).getOrNull()!!
    }

    // Strict: no save, no session ended, no log row when a rule refuses.
    private fun useCaseWith(users: UserRepository) =
        SetUserRolesUc(transactions, users, mockk<AuthSessionRepository>(), mockk<SecurityEventLog>(), mockk<AuditTrail>(), Clock.systemUTC())

    @Test
    fun `the one changing roles is read again under the lock - no longer an ADMIN, it is 403 FORBIDDEN`() = runTest {
        val users = mockk<UserRepository> {
            justRun { lockRoleChanges() }
            every { findById(UserId.constructFromPersisted(1)) } returns user(1, Role.OPERATOR)
        }

        val failure = useCaseWith(users).execute(SetUserRolesUc.Cmd("2", emptyList(), PanelActor(UserId.constructFromPersisted(1), null))).failureOrNull()

        assertEquals(SetUserRolesUc.Failure.NoLongerAdmin, failure)
        assertEquals("FORBIDDEN", failure?.code)
        verifyOrder {
            users.lockRoleChanges()
            users.findById(UserId.constructFromPersisted(1))
        }
    }

    @Test
    fun `taking ADMIN from the only one is 422 ROLE_CHANGE_NOT_ALLOWED with reason LAST_ADMIN`() = runTest {
        val users = mockk<UserRepository> {
            justRun { lockRoleChanges() }
            every { findById(UserId.constructFromPersisted(1)) } returns user(1, Role.ADMIN)
            every { findById(UserId.constructFromPersisted(2)) } returns user(2, Role.ADMIN)
            every { countAdmins() } returns 1
        }

        val failure = useCaseWith(users).execute(SetUserRolesUc.Cmd("2", listOf("OPERATOR"), PanelActor(UserId.constructFromPersisted(1), null))).failureOrNull()

        assertEquals(SetUserRolesUc.Failure.LastAdmin, failure)
        assertEquals("ROLE_CHANGE_NOT_ALLOWED", failure?.code)
        assertEquals(mapOf<String, Any>("reason" to "LAST_ADMIN"), failure?.meta)
    }
}
