package com.dawncourse.core.domain.model

/**
 * 本地备份数据结构
 *
 * 采用 JSON 序列化，避免直接拷贝数据库造成跨版本崩溃问题。
 * 该模型位于 Domain 层，不依赖 Android 框架类型。
 */
data class LocalBackupData(
    /** 备份文件版本号，用于兼容性校验 */
    val version: Int = CURRENT_VERSION,
    /** 导出时间戳（毫秒） */
    val exportTime: Long,
    /** 备份时的应用版本号 */
    val appVersionName: String,
    /** 当前应用设置快照 */
    val settings: AppSettings,
    /** 学期列表快照 */
    val semesters: List<Semester>,
    /** 课程列表快照 */
    val courses: List<Course>
) {
    companion object {
        /** 当前支持的本地备份结构版本 */
        const val CURRENT_VERSION = 1
    }
}

/**
 * 本地备份操作结果
 *
 * 用于向 UI 返回统一的成功/失败提示信息。
 */
data class LocalBackupResult(
    /** 是否成功 */
    val success: Boolean,
    /** 展示给用户的提示文案 */
    val message: String
)
