package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.data.local.startup.BackupRecoveryActivation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room 已提交而 DataStore 失败时的补偿协议。 */
class CompensatingBackupRestoreTest {

    @Test
    fun dataStoreFailureRestoresCompletePreImage() = runBlocking {
        val old = preImage(semesterId = 1L, name = "旧设置")
        val replacement = validated(semesterId = 2L, name = "新设置")
        var state = old
        var settingsWrites = 0
        var recoveryMarked = false

        val result = CompensatingBackupRestore.execute(
            preImage = old,
            replacement = replacement,
            replaceRoom = { profiles, semesters, courses, bindings ->
                state = state.copy(
                    profiles = profiles,
                    semesters = semesters,
                    courses = courses,
                    sourceBindings = bindings,
                )
            },
            replaceSettingsAndSelection = { settings, selection, activeProfileId ->
                settingsWrites += 1
                if (settingsWrites == 1) error("注入新 DataStore 写失败")
                state = state.copy(
                    settings = settings,
                    selectedSemesterId = selection,
                    activeProfileId = activeProfileId,
                )
            },
            enterRecoveryRequired = {
                recoveryMarked = true
                BackupRecoveryActivation.MarkerPersisted
            }
        )

        assertTrue(result.exceptionOrNull() is BackupRestoreRolledBackException)
        assertEquals(old, state)
        assertFalse(recoveryMarked)
    }

    @Test
    fun compensationFailurePersistsRecoveryRequiredMarker() = runBlocking {
        val old = preImage(semesterId = 1L, name = "旧设置")
        val replacement = validated(semesterId = 2L, name = "新设置")
        var room = old.semesters to old.courses
        var settingsWrites = 0
        var recoveryMarked = false

        val result = CompensatingBackupRestore.execute(
            preImage = old,
            replacement = replacement,
            replaceRoom = { _, semesters, courses, _ -> room = semesters to courses },
            replaceSettingsAndSelection = { _, _, _ ->
                settingsWrites += 1
                error(if (settingsWrites == 1) "新状态失败" else "补偿失败")
            },
            enterRecoveryRequired = {
                recoveryMarked = true
                BackupRecoveryActivation.MarkerPersisted
            }
        )

        assertTrue(result.exceptionOrNull() is BackupRecoveryRequiredException)
        assertEquals(
            BackupRecoveryActivation.MarkerPersisted,
            (result.exceptionOrNull() as BackupRecoveryRequiredException).recoveryActivation,
        )
        assertTrue(recoveryMarked)
        assertEquals(old.semesters to old.courses, room)
    }

    @Test
    fun markerFailureIsTypedAndNotOnlySuppressed() = runBlocking {
        val old = preImage(semesterId = 1L, name = "旧设置")
        val markerFailure = IllegalStateException("marker")
        var settingsWrites = 0

        val result = CompensatingBackupRestore.execute(
            preImage = old,
            replacement = validated(semesterId = 2L, name = "新设置"),
            replaceRoom = { _, _, _, _ -> },
            replaceSettingsAndSelection = { _, _, _ ->
                settingsWrites += 1
                error(if (settingsWrites == 1) "新状态失败" else "补偿失败")
            },
            enterRecoveryRequired = {
                BackupRecoveryActivation.MarkerPersistenceFailed(markerFailure)
            },
        )

        val failure = result.exceptionOrNull() as BackupRecoveryRequiredException
        assertEquals(
            BackupRecoveryActivation.MarkerPersistenceFailed(markerFailure),
            failure.recoveryActivation,
        )
        assertTrue(failure.suppressed.contains(markerFailure))
    }

    private fun preImage(semesterId: Long, name: String) = BackupRestorePreImage(
        settings = AppSettings(lastImportUrl = name),
        semesters = listOf(semester(semesterId)),
        courses = listOf(course(semesterId)),
        selectedSemesterId = semesterId,
        activeProfileId = 1L,
    )

    private fun validated(semesterId: Long, name: String) = ValidatedBackupRestore(
        settings = AppSettings(lastImportUrl = name),
        semesters = listOf(semester(semesterId)),
        courses = listOf(course(semesterId)),
        selectedSemesterId = semesterId,
        activeProfileId = 1L,
    )

    private fun semester(id: Long) = Semester(
        id = id,
        profileId = 1L,
        name = "学期$id",
        startDate = 1L,
        weekCount = 20,
        isCurrent = false,
    )

    private fun course(semesterId: Long) = Course(
        id = semesterId * 10,
        semesterId = semesterId,
        name = "课程$semesterId",
        dayOfWeek = 1,
        startSection = 1,
        duration = 2,
        startWeek = 1,
        endWeek = 16
    )
}
