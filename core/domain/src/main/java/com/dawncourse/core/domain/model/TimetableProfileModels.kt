package com.dawncourse.core.domain.model

/** 当前运行时课表选择；学期存在时必须属于当前 Profile。 */
data class ActiveTimetableContext(
    val profile: TimetableProfile,
    val semester: Semester? = null,
) {
    init {
        require(semester == null || semester.profileId == profile.id) {
            "当前学期必须属于当前课表"
        }
        require(semester == null || semester.id == profile.activeSemesterId) {
            "当前学期必须与课表当前学期指针一致"
        }
    }
}

/** 设置页所需的课表概览，避免 UI 直接访问 DAO 或自行汇总。 */
data class TimetableProfileSummary(
    val profile: TimetableProfile,
    val activeSemester: Semester?,
    val semesterCount: Int,
    val courseCount: Int,
    val isActive: Boolean,
)

/** 创建新学期所需的最小字段，Profile ID 由创建事务统一赋值。 */
data class NewSemesterSpec(
    val name: String,
    val startDate: Long,
    val weekCount: Int = 20,
)

/** 新课表创建方式，保证所有变体都显式描述目标名称。 */
sealed interface ProfileCreationRequest {
    /** 新课表展示名称。 */
    val name: String

    /** 创建没有学期与课程的空课表。 */
    data class Empty(override val name: String) : ProfileCreationRequest

    /** 创建一个包含单个初始学期的课表。 */
    data class WithSemester(
        override val name: String,
        val semester: NewSemesterSpec,
    ) : ProfileCreationRequest

    /** 克隆已有课表的学期、课程及来源绑定。 */
    data class Clone(
        override val name: String,
        val sourceProfileId: Long,
    ) : ProfileCreationRequest
}

/** 删除前展示的影响面；最后一个课表始终不可删除。 */
data class ProfileDeletionImpact(
    val profileId: Long,
    val profileName: String,
    val semesterCount: Int,
    val courseCount: Int,
    val sourceBindingCount: Int,
    /** Profile 独立凭据最多只有一份。 */
    val credentialCount: Int,
    val isActive: Boolean,
    val remainingProfileCount: Int,
) {
    /** 至少保留一个课表，避免运行时选择无可回退的聚合。 */
    val canDelete: Boolean get() = remainingProfileCount >= 1
}

/** 课表变更的显式结果，调用方不得以异常推断可恢复的业务失败。 */
sealed interface ProfileMutationResult {
    /** 变更成功；[activeContext] 为提交后的稳定运行时选择。 */
    data class Success(val activeContext: ActiveTimetableContext) : ProfileMutationResult

    /** 请求违反数据边界或业务不变量，数据库与运行时选择均未变更。 */
    data class Rejected(val reason: String) : ProfileMutationResult

    /**
     * 跨 Room、DataStore 或加密文件的补偿未能全部完成。
     * 调用方必须显式报告，不能把它伪装成“未发生变更”的 [Rejected]。
     */
    data class Inconsistent(
        val reason: String,
        val activeContext: ActiveTimetableContext? = null,
    ) : ProfileMutationResult
}
