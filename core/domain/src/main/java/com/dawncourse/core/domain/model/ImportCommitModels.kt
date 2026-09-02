package com.dawncourse.core.domain.model

/**
 * 导入数据的明确落点。
 *
 * 导入任务启动时即捕获该值，后续切换活动课表不会改变本次提交的归属。
 */
sealed interface ImportDestination {
    /** 在指定课表中新建并激活一个学期。 */
    data class NewSemester(val profileId: Long) : ImportDestination

    /** 新建独立课表及其首学期。 */
    data object NewProfile : ImportDestination

    /** 覆盖指定课表内的指定学期，绝不影响其他学期。 */
    data class OverwriteSemester(
        val profileId: Long,
        val semesterId: Long,
    ) : ImportDestination
}

/**
 * 一次导入提交的不可变输入。
 *
 * 课程的 [Course.semesterId] 会在数据层按 [destination] 重写，调用方提供的旧值不具备写入权限。
 */
data class ImportCommitRequest(
    val destination: ImportDestination,
    val semester: NewSemesterSpec,
    val courses: List<Course>,
    /** 仅 [ImportDestination.NewProfile] 使用，不能为空。 */
    val newProfileName: String? = null,
    /** 网络自动更新固定的来源绑定；普通导入保持为空。 */
    val expectedSourceBindingId: String? = null,
)

/** 覆盖前向前台展示的量化影响，不包含任何凭据内容。 */
data class ImportCommitImpact(
    val destination: ImportDestination,
    val profileName: String,
    val semesterName: String,
    val coursesToReplace: Int,
)

/** 导入事务的结构化结果，避免 UI 依赖异常文本判断是否已写入。 */
sealed interface ImportCommitResult {
    data class Success(
        val activeContext: ActiveTimetableContext,
        val committedCourseCount: Int,
    ) : ImportCommitResult

    data class Rejected(val reason: String) : ImportCommitResult

    /** Room 已提交但选择写入与补偿均失败，调用方必须进入恢复处理，绝不能当作普通拒绝。 */
    data class Inconsistent(val reason: String) : ImportCommitResult
}
