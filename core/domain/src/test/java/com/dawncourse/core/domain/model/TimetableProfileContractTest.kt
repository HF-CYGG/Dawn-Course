package com.dawncourse.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository

/** 多课表领域模型的最小契约，确保选择上下文不会混入跨 Profile 的学期。 */
class TimetableProfileContractTest {

    @Test
    fun activeContextRejectsSemesterOutsideProfile() {
        val profile = TimetableProfile(id = 7L, uuid = "profile-7", name = "主课表")
        val foreignSemester = Semester(
            id = 8L,
            profileId = 9L,
            name = "2026 秋",
            startDate = 0L,
        )

        val failure = runCatching {
            ActiveTimetableContext(profile = profile, semester = foreignSemester)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun activeContextRejectsSemesterThatIsNotTheProfileActiveSemester() {
        val profile = TimetableProfile(
            id = 7L,
            uuid = "profile-7",
            name = "主课表",
            activeSemesterId = 11L,
        )
        val staleSemester = Semester(
            id = 12L,
            profileId = 7L,
            name = "2026 春",
            startDate = 0L,
        )

        val failure = runCatching {
            ActiveTimetableContext(profile = profile, semester = staleSemester)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun creationRequestsDescribeEmptySemesterAndCloneModes() {
        val semester = NewSemesterSpec(name = "2026 秋", startDate = 1L, weekCount = 20)

        assertEquals("空课表", ProfileCreationRequest.Empty(name = "空课表").name)
        assertEquals(semester, ProfileCreationRequest.WithSemester(name = "新学期", semester = semester).semester)
        assertEquals(3L, ProfileCreationRequest.Clone(name = "副本", sourceProfileId = 3L).sourceProfileId)
    }

    @Test
    fun deletionImpactExposesWhetherDeletionIsAllowed() {
        val protected = ProfileDeletionImpact(
            profileId = 1L,
            profileName = "主课表",
            semesterCount = 0,
            courseCount = 0,
            sourceBindingCount = 0,
            credentialCount = 0,
            isActive = true,
            remainingProfileCount = 0,
        )
        val removable = protected.copy(remainingProfileCount = 1)

        assertFalse(protected.canDelete)
        assertTrue(removable.canDelete)
    }

    @Test
    fun repositoriesExposeProfileScopedMutationContracts() {
        val credentialMethods = CredentialsRepository::class.java.methods.associateBy { it.name }
        val profileMethods = TimetableProfileRepository::class.java.methods.associateBy { it.name }

        assertEquals(Long::class.javaPrimitiveType, credentialMethods.getValue("getCredentials").parameterTypes.first())
        assertTrue("copyCredentials" in credentialMethods)
        assertTrue("observeBoundProvider" in credentialMethods)
        assertTrue("createSemester" in profileMethods)
    }
}
