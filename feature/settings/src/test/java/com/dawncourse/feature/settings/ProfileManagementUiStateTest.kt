package com.dawncourse.feature.settings

import com.dawncourse.core.domain.model.ProfileCreationRequest
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多课表管理纯状态与创建参数契约。 */
class ProfileManagementUiStateTest {
    @Test
    fun emptyDraftBuildsEmptyRequest() {
        val result = ProfileCreationDraft(
            mode = ProfileCreationMode.EMPTY,
            profileName = "  空课表  ",
        ).buildRequest(currentProfileId = 7L, zoneId = ZoneOffset.UTC)

        assertEquals(
            ProfileCreationRequest.Empty(name = "空课表"),
            (result as ProfileCreationBuildResult.Success).request,
        )
    }

    @Test
    fun semesterDraftBuildsFirstSemesterRequest() {
        val result = ProfileCreationDraft(
            mode = ProfileCreationMode.WITH_SEMESTER,
            profileName = "主课表",
            semesterName = "2026 秋",
            startDate = "2026-09-07",
            weekCount = "18",
        ).buildRequest(currentProfileId = 7L, zoneId = ZoneOffset.UTC)

        val request = (result as ProfileCreationBuildResult.Success).request
            as ProfileCreationRequest.WithSemester
        assertEquals("主课表", request.name)
        assertEquals("2026 秋", request.semester.name)
        assertEquals(
            LocalDate.of(2026, 9, 7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            request.semester.startDate,
        )
        assertEquals(18, request.semester.weekCount)
    }

    @Test
    fun cloneDraftUsesCurrentProfileAsSource() {
        val result = ProfileCreationDraft(
            mode = ProfileCreationMode.CLONE_CURRENT,
            profileName = "课程副本",
        ).buildRequest(currentProfileId = 42L, zoneId = ZoneOffset.UTC)

        assertEquals(
            ProfileCreationRequest.Clone(name = "课程副本", sourceProfileId = 42L),
            (result as ProfileCreationBuildResult.Success).request,
        )
    }

    @Test
    fun draftRejectsMissingCloneSourceAndInvalidSemesterInput() {
        val missingSource = ProfileCreationDraft(
            mode = ProfileCreationMode.CLONE_CURRENT,
            profileName = "副本",
        ).buildRequest(currentProfileId = null, zoneId = ZoneOffset.UTC)
        val invalidSemester = ProfileCreationDraft(
            mode = ProfileCreationMode.WITH_SEMESTER,
            profileName = "课表",
            semesterName = "学期",
            startDate = "2026-02-30",
            weekCount = "0",
        ).buildRequest(currentProfileId = 1L, zoneId = ZoneOffset.UTC)

        assertEquals(
            ProfileFormError.NO_CLONE_SOURCE,
            (missingSource as ProfileCreationBuildResult.Error).error,
        )
        assertEquals(
            ProfileFormError.INVALID_DATE,
            (invalidSemester as ProfileCreationBuildResult.Error).error,
        )
    }

    @Test
    fun deleteConfirmationRequiresImpactAndMoreThanOneProfile() {
        val blocked = ProfileManagementUiState(
            profiles = listOf(ProfileRowUiModel(id = 1L, name = "唯一课表", isActive = true)),
            dialog = ProfileManagementDialog.DeletePreviewLoading(profileId = 1L),
        )
        val confirmable = blocked.copy(
            profiles = blocked.profiles + ProfileRowUiModel(id = 2L, name = "备用课表"),
            dialog = ProfileManagementDialog.DeleteConfirmation(
                impact = ProfileDeletionImpactUiModel(
                    profileId = 1L,
                    profileName = "唯一课表",
                    semesterCount = 2,
                    courseCount = 18,
                    sourceBindingCount = 1,
                    credentialCount = 1,
                ),
            ),
        )

        assertFalse(blocked.canConfirmDeletion)
        assertTrue(confirmable.canConfirmDeletion)
    }

    @Test
    fun emptyProfileRowExposesEmptySemesterStateWithoutFakeCourseCount() {
        val row = ProfileRowUiModel(
            id = 9L,
            name = "空课表",
            isActive = true,
            activeSemesterName = null,
            courseCount = null,
        )

        assertTrue(row.isEmptyProfile)
        assertEquals(null, row.courseCount)
    }

    @Test
    fun semesterDraftBuildsProfileScopedSemesterSpec() {
        val result = SemesterCreationDraft(
            semesterName = "2027 春",
            startDate = "2027-02-22",
            weekCount = "16",
        ).buildSpec(ZoneOffset.UTC)

        val spec = (result as SemesterCreationBuildResult.Success).semester
        assertEquals("2027 春", spec.name)
        assertEquals(16, spec.weekCount)
        assertEquals(
            LocalDate.of(2027, 2, 22).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            spec.startDate,
        )
    }
}
