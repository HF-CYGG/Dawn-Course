package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.SyncSourceBinding
import com.dawncourse.core.domain.model.TimetableProfile
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份在任何破坏性 Room 操作前的共享 fail-closed 门禁。 */
class BackupRestoreGateTest {
    private val gson = Gson()

    @Test
    fun jsonNullSettingsFailsBeforeCommit() = runBlocking {
        val backup = gson.fromJson(
            """
                {
                  "version":2,
                  "exportTime":1,
                  "appVersionName":"test",
                  "settings":null,
                  "semesters":[{"id":1,"name":"学期","startDate":1,"weekCount":20}],
                  "courses":[],
                  "selectedSemesterId":0
                }
            """.trimIndent(),
            LocalBackupData::class.java
        )
        var commitCalled = false

        val result = BackupRestoreGate.validateThenCommit(backup.toRestorePayload()) {
            commitCalled = true
        }

        assertTrue(result.isFailure)
        assertFalse(commitCalled)
    }

    @Test
    fun futureVersionFailsBeforeCommit() = runBlocking {
        var commitCalled = false
        val result = BackupRestoreGate.validateThenCommit(validPayload(version = 5)) {
            commitCalled = true
        }

        assertTrue(result.isFailure)
        assertFalse(commitCalled)
    }

    @Test
    fun nullSettingsFailsBeforeCommit() = runBlocking {
        var commitCalled = false
        val result = BackupRestoreGate.validateThenCommit(validPayload(settings = null)) {
            commitCalled = true
        }

        assertTrue(result.isFailure)
        assertFalse(commitCalled)
    }

    @Test
    fun danglingCourseReferenceFailsBeforeCommit() = runBlocking {
        var commitCalled = false
        val result = BackupRestoreGate.validateThenCommit(
            validPayload(courses = listOf(course(id = 21L, semesterId = 404L)))
        ) {
            commitCalled = true
        }

        assertTrue(result.isFailure)
        assertFalse(commitCalled)
    }

    @Test
    fun v2NullAndInvalidPositiveSelectionFailBeforeCommit() = runBlocking {
        var commitCount = 0

        val missing = BackupRestoreGate.validateThenCommit(validPayload(selectedSemesterId = null)) {
            commitCount += 1
        }
        val invalid = BackupRestoreGate.validateThenCommit(validPayload(selectedSemesterId = 99L)) {
            commitCount += 1
        }

        assertTrue(missing.isFailure)
        assertTrue(invalid.isFailure)
        assertEquals(0, commitCount)
    }

    @Test
    fun v1LegacySelectionIsResolvedBeforeCommit() = runBlocking {
        var selectedDuringCommit: Long? = null
        val payload = validPayload(
            version = 1,
            selectedSemesterId = null,
            semesters = listOf(
                semester(id = 8L, isCurrent = true),
                semester(id = 2L, isCurrent = true)
            ),
            courses = listOf(course(id = 21L, semesterId = 2L))
        )

        val result = BackupRestoreGate.validateThenCommit(payload) { validated ->
            selectedDuringCommit = validated.selectedSemesterId
        }

        assertTrue(result.isSuccess)
        assertEquals(2L, selectedDuringCommit)
    }

    @Test
    fun v2ExplicitZeroCommitsAsNoSelection() = runBlocking {
        var committed = false
        val result = BackupRestoreGate.validateThenCommit(validPayload(selectedSemesterId = 0L)) { validated ->
            committed = true
            assertNull(validated.selectedSemesterId)
        }

        assertTrue(result.isSuccess)
        assertTrue(committed)
    }

    @Test
    fun canonicalV4PreservesProfileSelectionAndSourceBinding() = runBlocking {
        val profile = TimetableProfile(
            id = 7L,
            uuid = "d8b80996-3127-4a4f-a348-ae9110805f56",
            name = "主课表",
            activeSemesterId = 11L,
        )
        val binding = SyncSourceBinding(
            sourceBindingId = "source-1",
            profileId = 7L,
            semesterId = 11L,
            provider = SyncProviderType.ZF,
            createdAt = 1L,
            updatedAt = 2L,
        )

        val result = BackupRestoreGate.validateThenCommit(
            validPayload(
                version = 4,
                semesters = listOf(semester(id = 11L, profileId = 7L)),
                courses = listOf(course(id = 21L, semesterId = 11L)),
                selectedSemesterId = null,
                profiles = listOf(profile),
                sourceBindings = listOf(binding),
                activeProfileId = 7L,
            ),
        ) { validated ->
            assertEquals(7L, validated.activeProfileId)
            assertEquals(11L, validated.selectedSemesterId)
            assertEquals(listOf(profile), validated.profiles)
            assertEquals(listOf(binding), validated.sourceBindings)
        }

        assertTrue(result.isSuccess)
    }

    @Test
    fun crossProfileOriginReferenceFailsBeforeCommit() = runBlocking {
        var commitCalled = false
        val result = BackupRestoreGate.validateThenCommit(
            validPayload(
                version = 4,
                semesters = listOf(
                    semester(id = 11L, profileId = 7L),
                    semester(id = 22L, profileId = 8L),
                ),
                courses = listOf(
                    course(id = 100L, semesterId = 11L),
                    course(id = 200L, semesterId = 22L, originId = 100L),
                ),
                selectedSemesterId = null,
                profiles = listOf(
                    TimetableProfile(7L, "d8b80996-3127-4a4f-a348-ae9110805f56", "A", 11L),
                    TimetableProfile(8L, "c8335904-33f5-4754-90a5-e289cb74b04b", "B", 22L),
                ),
                sourceBindings = emptyList(),
                activeProfileId = 7L,
            )
        ) { commitCalled = true }

        assertTrue(result.isFailure)
        assertFalse(commitCalled)
    }

    @Test
    fun sameOriginFamilyAcrossSemestersFailsBeforeCommitEvenWhenAnchorIsDangling() = runBlocking {
        var commitCalled = false
        val result = BackupRestoreGate.validateThenCommit(
            validPayload(
                version = 4,
                semesters = listOf(
                    semester(id = 11L, profileId = 7L),
                    semester(id = 12L, profileId = 7L),
                ),
                courses = listOf(
                    course(id = 201L, semesterId = 11L, originId = 999L),
                    course(id = 202L, semesterId = 12L, originId = 999L),
                ),
                selectedSemesterId = null,
                profiles = listOf(
                    TimetableProfile(7L, "d8b80996-3127-4a4f-a348-ae9110805f56", "A", 11L),
                ),
                sourceBindings = emptyList(),
                activeProfileId = 7L,
            )
        ) { commitCalled = true }

        assertTrue(result.isFailure)
        assertFalse(commitCalled)
    }

    @Test
    fun danglingOriginFamilyInsideOneSemesterRemainsCompatible() = runBlocking {
        val result = BackupRestoreGate.validateThenCommit(
            validPayload(
                version = 4,
                semesters = listOf(semester(id = 11L, profileId = 7L)),
                courses = listOf(
                    course(id = 201L, semesterId = 11L, originId = 999L),
                    course(id = 202L, semesterId = 11L, originId = 999L),
                ),
                selectedSemesterId = null,
                profiles = listOf(
                    TimetableProfile(7L, "d8b80996-3127-4a4f-a348-ae9110805f56", "A", 11L),
                ),
                sourceBindings = emptyList(),
                activeProfileId = 7L,
            )
        ) { }

        assertTrue(result.isSuccess)
    }

    private fun validPayload(
        version: Int = 2,
        settings: AppSettings? = AppSettings(),
        semesters: List<Semester>? = listOf(semester(id = 1L)),
        courses: List<Course>? = listOf(course(id = 21L, semesterId = 1L)),
        selectedSemesterId: Long? = 1L,
        profiles: List<TimetableProfile>? = null,
        sourceBindings: List<SyncSourceBinding>? = null,
        activeProfileId: Long? = null,
    ): BackupRestorePayload = BackupRestorePayload(
        version = version,
        timestamp = 1L,
        settings = settings,
        semesters = semesters,
        courses = courses,
        selectedSemesterId = selectedSemesterId,
        profiles = profiles,
        sourceBindings = sourceBindings,
        activeProfileId = activeProfileId,
    )

    private fun semester(
        id: Long,
        isCurrent: Boolean = false,
        profileId: Long = 1L,
    ) = Semester(
        id = id,
        profileId = profileId,
        name = "学期$id",
        startDate = 1L,
        weekCount = 20,
        isCurrent = isCurrent
    )

    private fun course(id: Long, semesterId: Long, originId: Long = 0L) = Course(
        id = id,
        semesterId = semesterId,
        name = "课程$id",
        dayOfWeek = 1,
        startSection = 1,
        duration = 2,
        startWeek = 1,
        endWeek = 16,
        originId = originId,
    )
}
