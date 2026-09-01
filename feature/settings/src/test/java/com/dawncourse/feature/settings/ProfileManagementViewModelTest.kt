package com.dawncourse.feature.settings

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.model.ProfileCreationRequest
import com.dawncourse.core.domain.model.ProfileDeletionImpact
import com.dawncourse.core.domain.model.ProfileMutationResult
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.TimetableProfileSummary
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用假仓库验证多课表 ViewModel 的用户操作与一次性事件。 */
class ProfileManagementViewModelTest {
    private val profile = TimetableProfile(
        id = 1L,
        uuid = "00000000-0000-0000-0000-000000000001",
        name = "主课表",
        activeSemesterId = 10L,
    )
    private val semester = Semester(
        id = 10L,
        profileId = profile.id,
        name = "2026 秋",
        startDate = 1L,
        weekCount = 18,
    )
    private val activeContext = ActiveTimetableContext(profile = profile, semester = semester)

    @Test
    fun summaryBuildsRowsWithRealCourseCountAndEmptyState() {
        val emptyProfile = profile.copy(
            id = 2L,
            uuid = "00000000-0000-0000-0000-000000000002",
            name = "空课表",
            activeSemesterId = null,
        )
        val repository = FakeProfileRepository(
            initialSummaries = listOf(
                TimetableProfileSummary(profile, semester, 1, 7, true),
                TimetableProfileSummary(emptyProfile, null, 0, 0, false),
            ),
            initialContext = activeContext,
            semesters = mapOf(profile.id to listOf(semester), emptyProfile.id to emptyList()),
        )
        val viewModel = createViewModel(repository)

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(7, state.profiles.first().courseCount)
        assertEquals("2026 秋", state.profiles.first().activeSemesterName)
        assertTrue(state.profiles.last().isEmptyProfile)
    }

    @Test
    fun threeCreationModesReachRepositoryUnchanged() {
        val repository = FakeProfileRepository(summary(), activeContext, mapOf(profile.id to listOf(semester)))
        val viewModel = createViewModel(repository)
        val drafts = listOf(
            ProfileCreationDraft(ProfileCreationMode.EMPTY, "空课表"),
            ProfileCreationDraft(
                mode = ProfileCreationMode.WITH_SEMESTER,
                profileName = "新学期",
                semesterName = "2027 春",
                startDate = "2027-02-22",
                weekCount = "16",
            ),
            ProfileCreationDraft(ProfileCreationMode.CLONE_CURRENT, "副本"),
        )

        drafts.forEach { draft ->
            viewModel.openCreateDialog()
            viewModel.updateCreateDraft(draft)
            viewModel.createProfile()
        }

        assertEquals(3, repository.created.size)
        assertTrue(repository.created[0] is ProfileCreationRequest.Empty)
        assertTrue(repository.created[1] is ProfileCreationRequest.WithSemester)
        assertEquals(profile.id, (repository.created[2] as ProfileCreationRequest.Clone).sourceProfileId)
    }

    @Test
    fun switchRenameSemesterAndCreateSemesterUseProfileRepository() {
        val repository = FakeProfileRepository(summary(), activeContext, mapOf(profile.id to listOf(semester)))
        val viewModel = createViewModel(repository)

        viewModel.switchProfile(profile.id)
        viewModel.renameProfile(profile.id, "新名称")
        viewModel.setActiveSemester(profile.id, semester.id)
        viewModel.createSemester(profile.id, "2027 春", "2027-02-22", "16")

        assertEquals(listOf(profile.id), repository.switched)
        assertEquals(listOf(profile.id to "新名称"), repository.renamed)
        assertEquals(listOf(profile.id to semester.id), repository.activeSemesters)
        assertEquals("2027 春", repository.createdSemesters.single().second.name)
    }

    @Test
    fun deletionRequiresPreviewAndShowsAllImpactCounts() = runBlocking {
        val second = profile.copy(
            id = 2L,
            uuid = "00000000-0000-0000-0000-000000000002",
            name = "备用",
            activeSemesterId = null,
        )
        val impact = ProfileDeletionImpact(
            profileId = profile.id,
            profileName = profile.name,
            semesterCount = 2,
            courseCount = 12,
            sourceBindingCount = 3,
            credentialCount = 1,
            isActive = true,
            remainingProfileCount = 2,
        )
        val repository = FakeProfileRepository(
            initialSummaries = summary() + TimetableProfileSummary(second, null, 0, 0, false),
            initialContext = activeContext,
            semesters = mapOf(profile.id to listOf(semester), second.id to emptyList()),
            deletionImpact = impact,
        )
        val viewModel = createViewModel(repository)

        viewModel.requestDeletion(profile.id)
        val dialog = viewModel.uiState
            .first { it.dialog is ProfileManagementDialog.DeleteConfirmation }
            .dialog as ProfileManagementDialog.DeleteConfirmation
        assertEquals(impact.profileName, dialog.impact.profileName)
        assertEquals(impact.sourceBindingCount, dialog.impact.sourceBindingCount)
        assertEquals(impact.credentialCount, dialog.impact.credentialCount)
        assertTrue(viewModel.uiState.first { it.canConfirmDeletion }.canConfirmDeletion)

        viewModel.confirmDeletion()
        assertEquals(listOf(profile.id), repository.deleted)
    }

    @Test
    fun lastProfileCannotRequestDeletion() = runBlocking {
        val repository = FakeProfileRepository(summary(), activeContext, mapOf(profile.id to listOf(semester)))
        val viewModel = createViewModel(repository)

        viewModel.requestDeletion(profile.id)

        assertTrue(repository.previewed.isEmpty())
        assertTrue(viewModel.uiState.value.dialog is ProfileManagementDialog.None)
        assertEquals(
            ProfileMutationOperation.DELETE_PROFILE,
            (viewModel.events.first() as ProfileManagementEvent.MutationRejected).operation,
        )
    }

    private fun summary(): List<TimetableProfileSummary> = listOf(
        TimetableProfileSummary(profile, semester, 1, 7, true),
    )

    private fun createViewModel(repository: TimetableProfileRepository): ProfileManagementViewModel =
        ProfileManagementViewModel(
            profileRepository = repository,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            zoneId = ZoneOffset.UTC,
        )

    /** 记录所有调用的内存假仓库。 */
    private class FakeProfileRepository(
        initialSummaries: List<TimetableProfileSummary>,
        initialContext: ActiveTimetableContext,
        semesters: Map<Long, List<Semester>>,
        private val deletionImpact: ProfileDeletionImpact? = null,
    ) : TimetableProfileRepository {
        private val profilesFlow = MutableStateFlow(initialSummaries.map(TimetableProfileSummary::profile))
        private val summariesFlow = MutableStateFlow(initialSummaries)
        private val contextFlow = MutableStateFlow<ActiveTimetableContext?>(initialContext)
        private val semesterFlows = semesters.mapValues { MutableStateFlow(it.value) }
        val created = mutableListOf<ProfileCreationRequest>()
        val createdSemesters = mutableListOf<Pair<Long, NewSemesterSpec>>()
        val switched = mutableListOf<Long>()
        val renamed = mutableListOf<Pair<Long, String>>()
        val activeSemesters = mutableListOf<Pair<Long, Long?>>()
        val previewed = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()

        override fun observeProfiles(): Flow<List<TimetableProfile>> = profilesFlow
        override fun observeActiveContext(): Flow<ActiveTimetableContext?> = contextFlow
        override fun observeSemesters(profileId: Long): Flow<List<Semester>> =
            semesterFlows.getValue(profileId)
        override fun observeProfileSummaries(): Flow<List<TimetableProfileSummary>> = summariesFlow
        override suspend fun switch(profileId: Long): ProfileMutationResult {
            switched += profileId
            return ProfileMutationResult.Success(contextFlow.value ?: error("missing context"))
        }
        override suspend fun setActiveSemester(profileId: Long, semesterId: Long?): ProfileMutationResult {
            activeSemesters += profileId to semesterId
            return ProfileMutationResult.Success(contextFlow.value ?: error("missing context"))
        }
        override suspend fun create(request: ProfileCreationRequest): ProfileMutationResult {
            created += request
            return ProfileMutationResult.Success(contextFlow.value ?: error("missing context"))
        }
        override suspend fun createSemester(
            profileId: Long,
            semester: NewSemesterSpec,
        ): ProfileMutationResult {
            createdSemesters += profileId to semester
            return ProfileMutationResult.Success(contextFlow.value ?: error("missing context"))
        }
        override suspend fun rename(profileId: Long, name: String): ProfileMutationResult {
            renamed += profileId to name
            return ProfileMutationResult.Success(contextFlow.value ?: error("missing context"))
        }
        override suspend fun previewDeletion(profileId: Long): ProfileDeletionImpact? {
            previewed += profileId
            return deletionImpact
        }
        override suspend fun delete(profileId: Long): ProfileMutationResult {
            deleted += profileId
            return ProfileMutationResult.Success(contextFlow.value ?: error("missing context"))
        }
    }
}
