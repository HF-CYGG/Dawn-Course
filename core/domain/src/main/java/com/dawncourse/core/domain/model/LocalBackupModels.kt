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
    val courses: List<Course>,
    /** v4 起保存自动更新来源绑定；凭据永不进入备份。 */
    val sourceBindings: List<SyncSourceBinding>? = null,
    /** v3 起保存完整 Profile 业务聚合；v1/v2 缺失。 */
    val profiles: List<TimetableProfile>? = null,
    /** v3 起保存当前 Profile；0 表示显式无选择。 */
    val activeProfileId: Long? = null,
    /**
     * 导出时选中的学期 ID。
     *
     * 旧 v1 文件没有该字段，Gson 会按 null 读取，再由旧 isCurrent 标记桥接。
     * v2 导出必须显式写入正数 ID 或 0；v2 的 null 属于无效输入。
     */
    val selectedSemesterId: Long? = null
) {
    companion object {
        /** 当前支持的本地备份结构版本 */
        const val CURRENT_VERSION = 4
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
    val message: String,
    /** 补偿也失败，必须进入 P0-5 RecoveryRequired 引导。 */
    val recoveryRequired: Boolean = false
)

/**
 * 本地备份预览信息
 *
 * 用于在还原前展示备份的核心元数据。
 */
data class LocalBackupPreview(
    /** 备份文件版本号 */
    val version: Int,
    /** 导出时间戳（毫秒） */
    val exportTime: Long,
    /** 备份时的应用版本号 */
    val appVersionName: String,
    /** 学期名称列表 */
    val semesterNames: List<String>,
    /** 学期数量 */
    val semesterCount: Int,
    /** 课程数量 */
    val courseCount: Int,
    /** Profile 数量。旧备份会在校验时规范化为单 Profile。 */
    val profileCount: Int = 1,
)

/**
 * 本地备份预览结果
 *
 * 成功时携带预览信息，失败时提供提示文案。
 */
data class LocalBackupPreviewResult(
    /** 是否成功 */
    val success: Boolean,
    /** 提示文案 */
    val message: String,
    /** 预览数据，失败时为空 */
    val preview: LocalBackupPreview? = null
)
