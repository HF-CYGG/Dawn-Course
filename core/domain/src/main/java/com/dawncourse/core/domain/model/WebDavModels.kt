package com.dawncourse.core.domain.model

/**
 * WebDAV 账号信息
 *
 * @property serverUrl WebDAV 服务器地址
 * @property username 账号
 * @property password 密码
 */
data class WebDavCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

/**
 * WebDAV 备份数据结构
 *
 * @property version 备份版本号，用于后续兼容升级
 * @property lastModified 备份生成时间戳
 * @property settings 应用设置快照
 * @property semesters 学期列表
 * @property courses 课程列表
 */
data class WebDavBackup(
    val version: Int = 1,
    val lastModified: Long,
    val settings: AppSettings,
    val semesters: List<Semester>,
    val courses: List<Course>
)

/**
 * WebDAV 同步结果
 *
 * @property success 是否成功
 * @property message 提示信息
 * @property code 错误码
 * @property remoteLastModified 云端更新时间戳
 * @property localLastModified 本地更新时间戳
 * @property requiresForceUpload 是否需要强制覆盖上传
 */
data class WebDavSyncResult(
    val success: Boolean,
    val message: String,
    val code: SyncErrorCode? = null,
    val remoteLastModified: Long? = null,
    val localLastModified: Long? = null,
    val requiresForceUpload: Boolean = false
)
