package com.dawncourse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.ProfileMutationResult
import com.dawncourse.core.domain.model.TimetableProfileSummary
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 多课表管理 ViewModel。
 *
 * 所有持久化操作只通过 [TimetableProfileRepository]，不访问 DAO 或系统 API。
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileManagementViewModel private constructor(
    private val profileRepository: TimetableProfileRepository,
    externalScope: CoroutineScope?,
    private val zoneId: ZoneId,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : ViewModel() {
    /** Hilt 生产构造器使用 ViewModel 生命周期与设备时区。 */
    @Inject
    constructor(profileRepository: TimetableProfileRepository) : this(
        profileRepository = profileRepository,
        externalScope = null,
        zoneId = ZoneId.systemDefault(),
        constructorMarker = Unit,
    )

    /** JVM 单测构造器可提供确定性协程作用域和时区。 */
    internal constructor(
        profileRepository: TimetableProfileRepository,
        coroutineScope: CoroutineScope,
        zoneId: ZoneId,
    ) : this(
        profileRepository = profileRepository,
        externalScope = coroutineScope,
        zoneId = zoneId,
        constructorMarker = Unit,
    )

    private val scope = externalScope ?: viewModelScope
    private val interactionState = MutableStateFlow(ProfileInteractionState())
    private val loadFailed = MutableStateFlow(false)
    private val eventChannel = Channel<ProfileManagementEvent>(Channel.BUFFERED)
    private val summaryState: StateFlow<ProfileSummaryLoadState> = profileRepository
        .observeProfileSummaries()
        .map<List<TimetableProfileSummary>, ProfileSummaryLoadState>(ProfileSummaryLoadState::Loaded)
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) {
                loadFailed.value = true
                eventChannel.trySend(ProfileManagementEvent.LoadFailed)
                emit(profileSummaryFailureState())
            } else throw failure
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ProfileSummaryLoadState.Loading,
        )
    private val activeContext: StateFlow<ActiveTimetableContext?> = profileRepository
        .observeActiveContext()
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) {
                loadFailed.value = true
                eventChannel.trySend(ProfileManagementEvent.LoadFailed)
                emit(null)
            } else throw failure
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
    private val semestersByProfile = summaryState
        .map { state -> (state as? ProfileSummaryLoadState.Loaded)?.summaries.orEmpty() }
        .flatMapLatest(::observeAllProfileSemesters)
        .catch { failure ->
            if (failure is CancellationException) throw failure
            if (failure is Exception) {
                loadFailed.value = true
                eventChannel.trySend(ProfileManagementEvent.LoadFailed)
                emit(emptyMap())
            } else throw failure
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyMap(),
        )

    /** 页面订阅的单一不可变状态。 */
    val uiState: StateFlow<ProfileManagementUiState> = combine(
        summaryState,
        activeContext,
        semestersByProfile,
        interactionState,
        loadFailed,
    ) { summaries, context, semesters, interaction, dependencyFailed ->
        val loadedSummaries = (summaries as? ProfileSummaryLoadState.Loaded)?.summaries.orEmpty()
        ProfileManagementUiState(
            isLoading = summaries is ProfileSummaryLoadState.Loading,
            hasLoadError = dependencyFailed || summaries is ProfileSummaryLoadState.Failed,
            isMutating = interaction.isMutating,
            profiles = loadedSummaries.map(TimetableProfileSummary::toUiModel),
            activeProfileId = context?.profile?.id,
            activeSemesterName = context?.semester?.name,
            semestersByProfile = semesters,
            dialog = interaction.dialog,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ProfileManagementUiState(),
    )

    /** 一次性语义事件，Compose 负责映射中英文文案。 */
    val events: Flow<ProfileManagementEvent> = eventChannel.receiveAsFlow()

    /** 打开三模式创建弹窗。 */
    fun openCreateDialog() {
        interactionState.update {
            it.copy(
                dialog = ProfileManagementDialog.Create(
                    ProfileCreationDraft(startDate = java.time.LocalDate.now(zoneId).toString()),
                ),
            )
        }
    }

    /** 更新创建表单快照。 */
    fun updateCreateDraft(draft: ProfileCreationDraft) {
        interactionState.update { state ->
            if (state.dialog is ProfileManagementDialog.Create) {
                state.copy(dialog = ProfileManagementDialog.Create(draft))
            } else {
                state
            }
        }
    }

    /** 校验并创建课表。 */
    fun createProfile() {
        val draft = (interactionState.value.dialog as? ProfileManagementDialog.Create)?.draft ?: return
        when (val result = draft.buildRequest(uiState.value.activeProfileId, zoneId)) {
            is ProfileCreationBuildResult.Error -> emit(ProfileManagementEvent.FormRejected(result.error))
            is ProfileCreationBuildResult.Success -> performMutation(
                operation = ProfileMutationOperation.CREATE_PROFILE,
                profileId = null,
                label = result.request.name,
            ) { profileRepository.create(result.request) }
        }
    }

    /** 点击课表卡片后立即切换。 */
    fun switchProfile(profileId: Long) {
        val label = uiState.value.profiles.firstOrNull { it.id == profileId }?.name
        performMutation(ProfileMutationOperation.SWITCH_PROFILE, profileId, label) {
            profileRepository.switch(profileId)
        }
    }

    /** 打开重命名弹窗。 */
    fun openRenameDialog(profileId: Long, currentName: String) {
        interactionState.update {
            it.copy(dialog = ProfileManagementDialog.Rename(profileId, currentName))
        }
    }

    /** 校验并重命名课表。 */
    fun renameProfile(profileId: Long, name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            emit(ProfileManagementEvent.FormRejected(ProfileFormError.EMPTY_PROFILE_NAME))
            return
        }
        performMutation(ProfileMutationOperation.RENAME_PROFILE, profileId, normalizedName) {
            profileRepository.rename(profileId, normalizedName)
        }
    }

    /** 打开指定课表的学期选择弹窗。 */
    fun openSemesterPicker(profileId: Long) {
        interactionState.update {
            it.copy(dialog = ProfileManagementDialog.SemesterPicker(profileId))
        }
    }

    /** 切换指定 Profile 的活动学期。 */
    fun setActiveSemester(profileId: Long, semesterId: Long) {
        val label = uiState.value.semestersByProfile[profileId]
            ?.firstOrNull { it.id == semesterId }
            ?.name
        performMutation(ProfileMutationOperation.SET_ACTIVE_SEMESTER, profileId, label) {
            profileRepository.setActiveSemester(profileId, semesterId)
        }
    }

    /** 打开指定 Profile 的新学期弹窗。 */
    fun openCreateSemesterDialog(profileId: Long) {
        interactionState.update {
            it.copy(dialog = ProfileManagementDialog.CreateSemester(profileId))
        }
    }

    /** 校验新学期字段，并在一个仓库事务中创建和激活。 */
    fun createSemester(profileId: Long, name: String, date: String, weekCount: String) {
        val draft = SemesterCreationDraft(name, date, weekCount)
        when (val result = draft.buildSpec(zoneId)) {
            is SemesterCreationBuildResult.Error -> emit(ProfileManagementEvent.FormRejected(result.error))
            is SemesterCreationBuildResult.Success -> performMutation(
                operation = ProfileMutationOperation.CREATE_SEMESTER,
                profileId = profileId,
                label = result.semester.name,
            ) { profileRepository.createSemester(profileId, result.semester) }
        }
    }

    /** 先读取真实影响面；最后一套课表不会进入预览。 */
    fun requestDeletion(profileId: Long) {
        if (!uiState.value.canMutate) {
            emit(ProfileManagementEvent.MutationRejected(ProfileMutationOperation.PREVIEW_DELETION))
            return
        }
        if (uiState.value.profiles.size <= 1) {
            emit(ProfileManagementEvent.MutationRejected(ProfileMutationOperation.DELETE_PROFILE))
            return
        }
        interactionState.update {
            it.copy(dialog = ProfileManagementDialog.DeletePreviewLoading(profileId))
        }
        scope.launch {
            val impact = try {
                profileRepository.previewDeletion(profileId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (impact == null || !impact.canDelete) {
                interactionState.update { it.copy(dialog = ProfileManagementDialog.None) }
                eventChannel.send(
                    ProfileManagementEvent.MutationRejected(ProfileMutationOperation.PREVIEW_DELETION),
                )
                return@launch
            }
            interactionState.update {
                it.copy(
                    dialog = ProfileManagementDialog.DeleteConfirmation(
                        ProfileDeletionImpactUiModel(
                            profileId = impact.profileId,
                            profileName = impact.profileName,
                            semesterCount = impact.semesterCount,
                            courseCount = impact.courseCount,
                            sourceBindingCount = impact.sourceBindingCount,
                            credentialCount = impact.credentialCount,
                        ),
                    ),
                )
            }
        }
    }

    /** 只删除当前已展示真实预览的 Profile。 */
    fun confirmDeletion() {
        val impact = (interactionState.value.dialog as? ProfileManagementDialog.DeleteConfirmation)?.impact
            ?: return
        if (uiState.value.profiles.size <= 1) {
            emit(ProfileManagementEvent.MutationRejected(ProfileMutationOperation.DELETE_PROFILE))
            return
        }
        performMutation(
            operation = ProfileMutationOperation.DELETE_PROFILE,
            profileId = impact.profileId,
            label = impact.profileName,
        ) { profileRepository.delete(impact.profileId) }
    }

    /** 关闭任意管理弹窗。 */
    fun dismissDialog() {
        interactionState.update { it.copy(dialog = ProfileManagementDialog.None) }
    }

    /** 组合各 Profile 的学期 Flow，并保持 Profile 边界。 */
    private fun observeAllProfileSemesters(
        summaries: List<TimetableProfileSummary>,
    ): Flow<Map<Long, List<ProfileSemesterUiModel>>> {
        if (summaries.isEmpty()) return flowOf(emptyMap())
        val flows = summaries.map { summary ->
            profileRepository.observeSemesters(summary.profile.id).map { semesters ->
                summary.profile.id to semesters.map { semester ->
                    ProfileSemesterUiModel(
                        id = semester.id,
                        name = semester.name,
                        weekCount = semester.weekCount,
                        isActive = semester.id == summary.profile.activeSemesterId,
                    )
                }
            }
        }
        return combine(flows) { entries -> entries.toMap() }
    }

    /** 统一处理 mutation 忙碌态、显式业务结果和异常边界。 */
    private fun performMutation(
        operation: ProfileMutationOperation,
        profileId: Long?,
        label: String?,
        mutation: suspend () -> ProfileMutationResult,
    ) {
        if (!uiState.value.canMutate || interactionState.value.isMutating) {
            emit(ProfileManagementEvent.MutationRejected(operation))
            return
        }
        interactionState.update { it.copy(isMutating = true) }
        scope.launch {
            val result = try {
                mutation()
            } catch (cancellation: CancellationException) {
                interactionState.update { it.copy(isMutating = false) }
                throw cancellation
            } catch (_: Exception) {
                null
            }
            interactionState.update {
                it.copy(
                    isMutating = false,
                    dialog = if (
                        result is ProfileMutationResult.Success ||
                        result is ProfileMutationResult.Inconsistent
                    ) {
                        ProfileManagementDialog.None
                    } else {
                        it.dialog
                    },
                )
            }
            eventChannel.send(
                when (result) {
                    is ProfileMutationResult.Success ->
                        ProfileManagementEvent.MutationSucceeded(operation, profileId, label)
                    is ProfileMutationResult.Inconsistent ->
                        ProfileManagementEvent.MutationInconsistent(operation)
                    else -> ProfileManagementEvent.MutationRejected(operation)
                },
            )
        }
    }

    /** 非挂起入口向缓冲 Channel 投递语义事件。 */
    private fun emit(event: ProfileManagementEvent) {
        eventChannel.trySend(event)
    }
}

/** 摘要首次发射前保持显式加载态。 */
internal sealed interface ProfileSummaryLoadState {
    data object Loading : ProfileSummaryLoadState
    data class Loaded(val summaries: List<TimetableProfileSummary>) : ProfileSummaryLoadState
    data object Failed : ProfileSummaryLoadState
}

/** 摘要根 Flow 异常时的显式安全状态。 */
internal fun profileSummaryFailureState(): ProfileSummaryLoadState = ProfileSummaryLoadState.Failed

/** 对话框与 mutation 忙碌态。 */
private data class ProfileInteractionState(
    val isMutating: Boolean = false,
    val dialog: ProfileManagementDialog = ProfileManagementDialog.None,
)

/** 领域摘要到稳定 UI 行的唯一映射。 */
private fun TimetableProfileSummary.toUiModel(): ProfileRowUiModel = ProfileRowUiModel(
    id = profile.id,
    name = profile.name,
    isActive = isActive,
    activeSemesterId = activeSemester?.id,
    activeSemesterName = activeSemester?.name,
    semesterCount = semesterCount,
    courseCount = courseCount,
)
