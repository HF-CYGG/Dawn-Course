package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/** 数据库双完整性扫描的同步/后台决策边界。 */
class IntegrityVerificationPolicyTest {
    @Test
    fun everyRecoveryOrKeyTransitionResponsibilityForcesSynchronousVerification() {
        val forcingInputs = listOf<(IntegrityVerificationPolicyInput) -> IntegrityVerificationPolicyInput>(
            { it.copy(recoveredIncompleteMigrationThisRun = true) },
            { it.copy(recoveryResponsibilityMarkerPresent = true) },
            { it.copy(previousDatabaseStartupIncomplete = true) },
            { it.copy(migratedPlaintextThisRun = true) },
            { it.copy(rekeyOrKeyModeChangedThisRun = true) },
            { it.copy(persistentStateUnreadable = true) },
        )

        forcingInputs.forEach { force ->
            assertEquals(
                IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
                IntegrityVerificationPolicy.decide(force(recentHealthyInput())),
            )
        }
    }

    @Test
    fun missingOrInvalidTimestampForcesSynchronousVerification() {
        listOf<Long?>(null, 0L, -1L).forEach { timestamp ->
            assertEquals(
                IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
                IntegrityVerificationPolicy.decide(
                    recentHealthyInput().copy(lastSuccessfulVerificationEpochMillis = timestamp),
                ),
            )
        }
    }

    @Test
    fun exactlySevenDaysForcesSynchronousVerificationButOneMillisecondEarlierDoesNot() {
        val now = 20L * DAY_MILLIS

        assertEquals(
            IntegrityVerificationMode.BACKGROUND_AFTER_READY,
            IntegrityVerificationPolicy.decide(
                recentHealthyInput().copy(
                    nowEpochMillis = now,
                    lastSuccessfulVerificationEpochMillis = now - SEVEN_DAYS_MILLIS + 1L,
                ),
            ),
        )
        assertEquals(
            IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
            IntegrityVerificationPolicy.decide(
                recentHealthyInput().copy(
                    nowEpochMillis = now,
                    lastSuccessfulVerificationEpochMillis = now - SEVEN_DAYS_MILLIS,
                ),
            ),
        )
    }

    @Test
    fun clockRollbackOrInvalidCurrentTimeForcesSynchronousVerification() {
        assertEquals(
            IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
            IntegrityVerificationPolicy.decide(
                recentHealthyInput().copy(
                    nowEpochMillis = 99L,
                    lastSuccessfulVerificationEpochMillis = 100L,
                ),
            ),
        )
        assertEquals(
            IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY,
            IntegrityVerificationPolicy.decide(recentHealthyInput().copy(nowEpochMillis = 0L)),
        )
    }

    private fun recentHealthyInput(): IntegrityVerificationPolicyInput =
        IntegrityVerificationPolicyInput(
            recoveredIncompleteMigrationThisRun = false,
            recoveryResponsibilityMarkerPresent = false,
            previousDatabaseStartupIncomplete = false,
            migratedPlaintextThisRun = false,
            rekeyOrKeyModeChangedThisRun = false,
            persistentStateUnreadable = false,
            nowEpochMillis = 10L * DAY_MILLIS,
            lastSuccessfulVerificationEpochMillis = 9L * DAY_MILLIS,
        )

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val SEVEN_DAYS_MILLIS = 7L * DAY_MILLIS
    }
}
