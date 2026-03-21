package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.AppDatabase
import androidx.room.withTransaction
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.LastSyncInfo
import com.dawncourse.core.domain.model.SyncErrorCode
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.WebDavBackup
import com.dawncourse.core.domain.model.WebDavCredentials
import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.SyncStateRepository
import com.dawncourse.core.domain.repository.WebDavCredentialsRepository
import com.dawncourse.core.domain.repository.WebDavSyncRepository
import com.google.gson.GsonBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV 同步仓库实现
 *
 * 负责：
 * 1. 从本地数据库与设置中构建备份数据
 * 2. 通过 WebDAV 上传/下载备份文件
 * 3. 处理冲突与同步状态记录
 */
@Singleton
class WebDavSyncRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val webDavCredentialsRepository: WebDavCredentialsRepository,
    private val syncStateRepository: SyncStateRepository
) : WebDavSyncRepository {

    /** JSON 序列化工具 */
    private val gson = GsonBuilder().create()
    /** 复用的 OkHttpClient */
    private val client = OkHttpClient()

    /**
     * 拉取云端备份信息
     *
     * - 200：云端存在备份并解析 lastModified
     * - 404：云端不存在备份
     * - 401/403：认证失败
     * - 其他：访问失败
     */
    override suspend fun fetchRemoteInfo(): WebDavSyncResult {
        val creds = webDavCredentialsRepository.getCredentials()
            ?: return WebDavSyncResult(false, "未配置 WebDAV 账号", SyncErrorCode.NO_CREDENTIALS)

        val localNow = System.currentTimeMillis()
        val response = downloadBackupJson(creds)
        return when (response.code) {
            200 -> {
                val backup = parseBackup(response.body)
                    ?: return WebDavSyncResult(false, "云端数据解析失败", SyncErrorCode.PARSE_ERROR)
                WebDavSyncResult(
                    success = true,
                    message = "云端已存在备份",
                    remoteLastModified = backup.lastModified,
                    localLastModified = localNow
                )
            }
            404 -> {
                WebDavSyncResult(
                    success = true,
                    message = "云端未找到备份",
                    remoteLastModified = null,
                    localLastModified = localNow
                )
            }
            -1 -> WebDavSyncResult(false, "连接失败", SyncErrorCode.NETWORK_ERROR)
            401, 403 -> WebDavSyncResult(false, "账号或密码错误", SyncErrorCode.AUTH_FAILED)
            else -> WebDavSyncResult(false, "云端访问失败", SyncErrorCode.SERVER_ERROR)
        }
    }

    /**
     * 上传本地备份到 WebDAV
     *
     * 1. 先拉取云端备份做冲突检测
     * 2. 冲突且未强制时返回 requiresForceUpload
     * 3. 构建本地备份并上传
     * 4. 写入同步结果到 SyncState
     */
    override suspend fun uploadBackup(forceUpload: Boolean): WebDavSyncResult {
        val creds = webDavCredentialsRepository.getCredentials()
            ?: return WebDavSyncResult(false, "未配置 WebDAV 账号", SyncErrorCode.NO_CREDENTIALS)

        val localSyncTs = getLastWebDavSyncTime()
        val remote = downloadBackupJson(creds)
        if (remote.code == -1) {
            return WebDavSyncResult(false, "连接失败", SyncErrorCode.NETWORK_ERROR)
        }
        if (remote.code == 200) {
            val remoteBackup = parseBackup(remote.body)
            if (remoteBackup != null && localSyncTs > 0 && remoteBackup.lastModified > localSyncTs && !forceUpload) {
                return WebDavSyncResult(
                    success = false,
                    message = "云端存在更新的数据",
                    code = SyncErrorCode.SERVER_ERROR,
                    remoteLastModified = remoteBackup.lastModified,
                    localLastModified = localSyncTs,
                    requiresForceUpload = true
                )
            }
        } else if (remote.code == 401 || remote.code == 403) {
            return WebDavSyncResult(false, "账号或密码错误", SyncErrorCode.AUTH_FAILED)
        } else if (remote.code !in listOf(404, 200)) {
            return WebDavSyncResult(false, "云端访问失败", SyncErrorCode.SERVER_ERROR)
        }

        val backup = buildLocalBackup()
        val body = gson.toJson(backup)
        val created = createDirectoryIfNeeded(creds)
        if (!created) {
            return WebDavSyncResult(false, "无法创建云端目录", SyncErrorCode.SERVER_ERROR)
        }
        val uploaded = uploadBackupJson(creds, body)
        return if (uploaded) {
            syncStateRepository.setLastSyncInfo(
                LastSyncInfo(
                    timestamp = backup.lastModified,
                    success = true,
                    message = "已上传 ${backup.courses.size} 门课程",
                    provider = SyncProviderType.WEBDAV
                )
            )
            WebDavSyncResult(true, "上传成功", remoteLastModified = backup.lastModified, localLastModified = backup.lastModified)
        } else {
            syncStateRepository.setLastSyncInfo(
                LastSyncInfo(
                    timestamp = System.currentTimeMillis(),
                    success = false,
                    message = "上传失败",
                    provider = SyncProviderType.WEBDAV
                )
            )
            WebDavSyncResult(false, "上传失败", SyncErrorCode.SERVER_ERROR)
        }
    }

    /**
     * 从 WebDAV 下载备份并恢复到本地
     *
     * - 成功后会覆盖课程/学期数据，并批量覆盖设置
     * - 同时写入同步状态
     */
    override suspend fun downloadBackup(): WebDavSyncResult {
        val creds = webDavCredentialsRepository.getCredentials()
            ?: return WebDavSyncResult(false, "未配置 WebDAV 账号", SyncErrorCode.NO_CREDENTIALS)

        val response = downloadBackupJson(creds)
        if (response.code == -1) {
            return WebDavSyncResult(false, "连接失败", SyncErrorCode.NETWORK_ERROR)
        }
        if (response.code == 404) {
            return WebDavSyncResult(false, "云端未找到备份", SyncErrorCode.SERVER_ERROR)
        }
        if (response.code == 401 || response.code == 403) {
            return WebDavSyncResult(false, "账号或密码错误", SyncErrorCode.AUTH_FAILED)
        }
        if (response.code != 200) {
            return WebDavSyncResult(false, "云端访问失败", SyncErrorCode.SERVER_ERROR)
        }
        val backup = parseBackup(response.body)
            ?: return WebDavSyncResult(false, "云端数据解析失败", SyncErrorCode.PARSE_ERROR)

        applyBackup(backup)
        syncStateRepository.setLastSyncInfo(
            LastSyncInfo(
                timestamp = System.currentTimeMillis(),
                success = true,
                message = "已恢复 ${backup.courses.size} 门课程",
                provider = SyncProviderType.WEBDAV
            )
        )
        return WebDavSyncResult(true, "恢复成功", remoteLastModified = backup.lastModified, localLastModified = backup.lastModified)
    }

    /**
     * 构建本地备份
     *
     * 包含设置、学期、课程三部分，并写入 lastModified 时间戳。
     */
    private suspend fun buildLocalBackup(): WebDavBackup {
        val settings = settingsRepository.settings.first()
        val semesters = database.semesterDao().getAllSemestersOnce().map { it.toDomain() }
        val courses = database.courseDao().getAllCoursesOnce().map { it.toDomain() }
        return WebDavBackup(
            version = 1,
            lastModified = System.currentTimeMillis(),
            settings = settings,
            semesters = semesters,
            courses = courses
        )
    }

    /**
     * 应用云端备份到本地
     *
     * 通过数据库事务保证课程/学期的替换原子性，避免中途失败造成数据不一致。
     */
    private suspend fun applyBackup(backup: WebDavBackup) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                database.courseDao().deleteAllCourses()
                database.semesterDao().deleteAllSemesters()
                backup.semesters.forEach { database.semesterDao().insertSemester(it.toEntity()) }
                backup.courses.forEach { database.courseDao().insertCourse(it.toEntity()) }
            }
        }
        settingsRepository.setAllSettings(backup.settings)
    }

    /**
     * 获取上一次 WebDAV 同步时间
     *
     * 仅在 provider=WEBDAV 时返回时间戳，否则返回 0。
     */
    private suspend fun getLastWebDavSyncTime(): Long {
        val info = syncStateRepository.lastSyncInfo.first()
        return if (info.provider == SyncProviderType.WEBDAV) info.timestamp else 0L
    }

    /**
     * 解析备份 JSON
     */
    private fun parseBackup(json: String?): WebDavBackup? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, WebDavBackup::class.java) }.getOrNull()
    }

    /**
     * WebDAV 请求响应结构
     *
     * @property code HTTP 状态码
     * @property body 响应体内容
     */
    private data class WebDavResponse(val code: Int, val body: String?)

    /**
     * 下载云端备份 JSON
     */
    private suspend fun downloadBackupJson(creds: WebDavCredentials): WebDavResponse {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildFileUrl(creds.serverUrl))
                .header("Authorization", Credentials.basic(creds.username, creds.password))
                .get()
                .build()
            try {
                val response = client.newCall(request).execute()
                WebDavResponse(response.code, response.body?.string())
            } catch (_: Exception) {
                WebDavResponse(-1, null)
            }
        }
    }

    /**
     * 上传备份 JSON
     */
    private suspend fun uploadBackupJson(creds: WebDavCredentials, json: String): Boolean {
        return withContext(Dispatchers.IO) {
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(buildFileUrl(creds.serverUrl))
                .header("Authorization", Credentials.basic(creds.username, creds.password))
                .put(body)
                .build()
            try {
                client.newCall(request).execute().isSuccessful
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 创建 WebDAV 目录（若已存在则忽略）
     */
    private suspend fun createDirectoryIfNeeded(creds: WebDavCredentials): Boolean {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildDirUrl(creds.serverUrl))
                .header("Authorization", Credentials.basic(creds.username, creds.password))
                .method("MKCOL", null)
                .build()
            try {
                val response = client.newCall(request).execute()
                response.isSuccessful || response.code == 405
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 构建备份目录 URL
     */
    private fun buildDirUrl(serverUrl: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}DawnCourseBackup/"
    }

    /**
     * 构建备份文件 URL
     */
    private fun buildFileUrl(serverUrl: String): String {
        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        return "${base}DawnCourseBackup/backup_v1.json"
    }
}
