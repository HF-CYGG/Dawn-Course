package com.dawncourse.feature.settings

import androidx.compose.runtime.Immutable
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.model.ProfileCreationRequest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** 新课表创建模式；产品不提供归档模式。 */
enum class ProfileCreationMode {
    EMPTY,
    WITH_SEMESTER,
    CLONE_CURRENT,
}

/** 创建表单的不可变输入快照。 */
@Immutable
data class ProfileCreationDraft(
    val mode: ProfileCreationMode = ProfileCreationMode.EMPTY,
    val profileName: String = "",
    val semesterName: String = "",
    val startDate: String = "",
    val weekCount: String = "20",
)

/** 在既有 Profile 内创建学期的表单快照。 */
@Immutable
data class SemesterCreationDraft(
    val semesterName: String,
    val startDate: String,
    val weekCount: String,
)

/** 新学期参数校验结果。 */
sealed interface SemesterCreationBuildResult {
    data class Success(val semester: NewSemesterSpec) : SemesterCreationBuildResult
    data class Error(val error: ProfileFormError) : SemesterCreationBuildResult
}

/** 创建参数校验结果。 */
sealed interface ProfileCreationBuildResult {
    /** 表单合法并已转换为领域请求。 */
    data class Success(val request: ProfileCreationRequest) : ProfileCreationBuildResult

    /** 表单不合法；界面将语义错误映射为本地化文案。 */
    data class Error(val error: ProfileFormError) : ProfileCreationBuildResult
}

/** 表单错误语义，不在 ViewModel 中拼接用户可见文案。 */
enum class ProfileFormError {
    EMPTY_PROFILE_NAME,
    EMPTY_SEMESTER_NAME,
    INVALID_DATE,
    INVALID_WEEK_COUNT,
    NO_CLONE_SOURCE,
}

/** 将用户表单转换为领域请求，不触碰 Android Context。 */
fun ProfileCreationDraft.buildRequest(
    currentProfileId: Long?,
    zoneId: ZoneId,
): ProfileCreationBuildResult {
    val normalizedProfileName = profileName.trim()
    if (normalizedProfileName.isEmpty()) {
        return ProfileCreationBuildResult.Error(ProfileFormError.EMPTY_PROFILE_NAME)
    }
    return when (mode) {
        ProfileCreationMode.EMPTY -> ProfileCreationBuildResult.Success(
            ProfileCreationRequest.Empty(name = normalizedProfileName),
        )

        ProfileCreationMode.CLONE_CURRENT -> {
            val sourceProfileId = currentProfileId
                ?: return ProfileCreationBuildResult.Error(ProfileFormError.NO_CLONE_SOURCE)
            ProfileCreationBuildResult.Success(
                ProfileCreationRequest.Clone(
                    name = normalizedProfileName,
                    sourceProfileId = sourceProfileId,
                ),
            )
        }

        ProfileCreationMode.WITH_SEMESTER -> buildSemesterRequest(normalizedProfileName, zoneId)
    }
}

/** 校验首学期名称、日期和周数，并构造含首学期请求。 */
private fun ProfileCreationDraft.buildSemesterRequest(
    normalizedProfileName: String,
    zoneId: ZoneId,
): ProfileCreationBuildResult {
    val normalizedSemesterName = semesterName.trim()
    if (normalizedSemesterName.isEmpty()) {
        return ProfileCreationBuildResult.Error(ProfileFormError.EMPTY_SEMESTER_NAME)
    }
    val parsedDate = try {
        LocalDate.parse(startDate.trim())
    } catch (_: DateTimeException) {
        return ProfileCreationBuildResult.Error(ProfileFormError.INVALID_DATE)
    }
    val parsedWeekCount = weekCount.trim().toIntOrNull()
        ?: return ProfileCreationBuildResult.Error(ProfileFormError.INVALID_WEEK_COUNT)
    if (parsedWeekCount !in 1..40) {
        return ProfileCreationBuildResult.Error(ProfileFormError.INVALID_WEEK_COUNT)
    }
    return ProfileCreationBuildResult.Success(
        ProfileCreationRequest.WithSemester(
            name = normalizedProfileName,
            semester = NewSemesterSpec(
                name = normalizedSemesterName,
                startDate = parsedDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                weekCount = parsedWeekCount,
            ),
        ),
    )
}

/** 校验既有 Profile 的新学期表单并转换为领域参数。 */
fun SemesterCreationDraft.buildSpec(zoneId: ZoneId): SemesterCreationBuildResult {
    val normalizedName = semesterName.trim()
    if (normalizedName.isEmpty()) {
        return SemesterCreationBuildResult.Error(ProfileFormError.EMPTY_SEMESTER_NAME)
    }
    val parsedDate = try {
        LocalDate.parse(startDate.trim())
    } catch (_: DateTimeException) {
        return SemesterCreationBuildResult.Error(ProfileFormError.INVALID_DATE)
    }
    val parsedWeekCount = weekCount.trim().toIntOrNull()
        ?: return SemesterCreationBuildResult.Error(ProfileFormError.INVALID_WEEK_COUNT)
    if (parsedWeekCount !in 1..40) {
        return SemesterCreationBuildResult.Error(ProfileFormError.INVALID_WEEK_COUNT)
    }
    return SemesterCreationBuildResult.Success(
        NewSemesterSpec(
            name = normalizedName,
            startDate = parsedDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            weekCount = parsedWeekCount,
        ),
    )
}

/** 设置页课表列表单项；未知课程数保持 null，禁止伪造为零。 */
@Immutable
data class ProfileRowUiModel(
    val id: Long,
    val name: String,
    val isActive: Boolean = false,
    val activeSemesterId: Long? = null,
    val activeSemesterName: String? = null,
    val semesterCount: Int = 0,
    val courseCount: Int? = null,
) {
    /** 完全没有学期时才展示空 Profile 引导。 */
    val isEmptyProfile: Boolean get() = semesterCount == 0
}

/** 删除确认对话框只接收真实预览结果。 */
@Immutable
data class ProfileDeletionImpactUiModel(
    val profileId: Long,
    val profileName: String,
    val semesterCount: Int,
    val courseCount: Int,
    val sourceBindingCount: Int,
    val credentialCount: Int,
)

/** 管理页面的互斥对话框状态。 */
sealed interface ProfileManagementDialog {
    data object None : ProfileManagementDialog
    data class Create(val draft: ProfileCreationDraft = ProfileCreationDraft()) : ProfileManagementDialog
    data class Rename(val profileId: Long, val currentName: String) : ProfileManagementDialog
    data class SemesterPicker(val profileId: Long) : ProfileManagementDialog
    data class CreateSemester(val profileId: Long) : ProfileManagementDialog
    data class DeletePreviewLoading(val profileId: Long) : ProfileManagementDialog
    data class DeleteConfirmation(val impact: ProfileDeletionImpactUiModel) : ProfileManagementDialog
}

/** 多课表管理页面的唯一不可变状态。 */
@Immutable
data class ProfileManagementUiState(
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val profiles: List<ProfileRowUiModel> = emptyList(),
    val activeProfileId: Long? = null,
    val activeSemesterName: String? = null,
    val semestersByProfile: Map<Long, List<ProfileSemesterUiModel>> = emptyMap(),
    val dialog: ProfileManagementDialog = ProfileManagementDialog.None,
) {
    /** 永久删除必须同时满足“已有真实预览”和“不是最后一套”。 */
    val canConfirmDeletion: Boolean
        get() = profiles.size > 1 && dialog is ProfileManagementDialog.DeleteConfirmation
}

/** 学期选择列表单项。 */
@Immutable
data class ProfileSemesterUiModel(
    val id: Long,
    val name: String,
    val weekCount: Int,
    val isActive: Boolean,
)

/** 一次性 UI 事件，供 Snackbar 与主线后续对账接线。 */
sealed interface ProfileManagementEvent {
    data class MutationSucceeded(
        val operation: ProfileMutationOperation,
        val profileId: Long? = null,
        val label: String? = null,
    ) : ProfileManagementEvent

    data class MutationRejected(val operation: ProfileMutationOperation) : ProfileManagementEvent

    /** 跨存储补偿未完整完成，必须向用户明确报告而非伪装成普通业务拒绝。 */
    data class MutationInconsistent(val operation: ProfileMutationOperation) : ProfileManagementEvent

    data class FormRejected(val error: ProfileFormError) : ProfileManagementEvent
}

/** 用户可见操作类型，由 Compose 映射为本地化 Snackbar。 */
enum class ProfileMutationOperation {
    CREATE_PROFILE,
    SWITCH_PROFILE,
    RENAME_PROFILE,
    SET_ACTIVE_SEMESTER,
    CREATE_SEMESTER,
    DELETE_PROFILE,
    PREVIEW_DELETION,
}

/** 稳定语义标签，供 Compose 与源码契约测试复用。 */
object ProfileManagementTestTags {
    const val SCREEN = "profile_management_screen"
    const val PROFILE_LIST = "profile_management_list"
    const val CREATE_BUTTON = "profile_management_create"
    const val CREATE_DIALOG = "profile_management_create_dialog"
    const val DELETE_DIALOG = "profile_management_delete_dialog"
    const val EMPTY_SEMESTER = "profile_management_empty_semester"
    fun profile(profileId: Long): String = "profile_management_item_$profileId"
    fun import(profileId: Long): String = "profile_management_import_$profileId"
}
