package com.dawncourse.core.data.repository

import android.content.Context
import android.net.Uri
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.LocalBackupPreview
import com.dawncourse.core.domain.model.LocalBackupPreviewResult
import com.dawncourse.core.domain.model.LocalBackupResult
import com.dawncourse.core.domain.repository.LocalBackupRepository
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地备份仓库实现
 *
 * 使用 JSON + SAF 进行备份与还原：
 * - 备份时读取数据库与设置快照，序列化后写入 URI
 * - 还原时解析 JSON 并在事务中覆盖数据库，再恢复设置
 */
@Singleton
class LocalBackupRepositoryImpl @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val backupSnapshotBuilder: BackupSnapshotBuilder,
    private val backupRestoreCoordinator: BackupRestoreCoordinator
) : LocalBackupRepository {

    /** JSON 序列化工具 */
    private val gson = GsonBuilder().create()

    /**
     * 解析备份文件为结构化数据
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    private fun parseBackupFromUri(uri: String): Result<LocalBackupData> {
        return runCatching {
            val parsedUri = Uri.parse(uri)
            val inputStream = context.contentResolver.openInputStream(parsedUri)
            if (inputStream == null) {
                throw IllegalStateException("无法读取备份文件")
            }
            val json = inputStream.use { it.bufferedReader().readText() }
            gson.fromJson(json, LocalBackupData::class.java)
        }
    }

    /**
     * 导出备份到 SAF 文件
     */
    override suspend fun exportToUri(uri: String): LocalBackupResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val parsedUri = Uri.parse(uri)
                // 读取设置与数据库快照
                val snapshot = backupSnapshotBuilder.build()
                // 获取应用版本号用于写入元数据
                val versionName = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
                // 组装备份对象并序列化为 JSON
                val backup = LocalBackupData(
                    exportTime = System.currentTimeMillis(),
                    appVersionName = versionName,
                    settings = snapshot.settings,
                    semesters = snapshot.semesters,
                    courses = snapshot.courses,
                    selectedSemesterId = null,
                    profiles = snapshot.profiles,
                    sourceBindings = snapshot.sourceBindings,
                    activeProfileId = snapshot.activeProfileId,
                )
                val json = gson.toJson(backup)
                // 写入 SAF 输出流
                val outputStream = context.contentResolver.openOutputStream(parsedUri)
                if (outputStream == null) {
                    return@runCatching LocalBackupResult(false, "无法写入备份文件")
                }
                outputStream.use { it.write(json.toByteArray()) }
                LocalBackupResult(true, "备份已保存")
            }.getOrElse { error ->
                LocalBackupResult(false, "备份失败：${error.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 从 SAF 文件导入备份
     *
     * 还原时必须保证数据库替换的原子性，避免只恢复一半的数据。
     */
    override suspend fun importFromUri(uri: String): LocalBackupResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                // 解析并校验备份文件
                val backup = parseBackupFromUri(uri).getOrElse { error ->
                    return@runCatching LocalBackupResult(false, "无法读取备份文件：${error.message ?: "未知错误"}")
                }
                BackupRestoreGate.validateThenCommit(backup.toRestorePayload()) { snapshot ->
                    backupRestoreCoordinator.restore(snapshot).getOrThrow()
                }.getOrElse { error ->
                    if (error is BackupRecoveryRequiredException) {
                        return@runCatching LocalBackupResult(
                            success = false,
                            message = "数据补偿恢复失败，需要进入恢复流程",
                            recoveryRequired = true
                        )
                    }
                    return@runCatching LocalBackupResult(
                        false,
                        error.message ?: "备份校验或还原失败"
                    )
                }
                LocalBackupResult(true, "已完成数据还原")
            }.getOrElse { error ->
                LocalBackupResult(false, "还原失败：${error.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 读取备份预览信息
     */
    override suspend fun readPreview(uri: String): LocalBackupPreviewResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val backup = parseBackupFromUri(uri).getOrElse { error ->
                    return@runCatching LocalBackupPreviewResult(
                        success = false,
                        message = "无法读取备份文件：${error.message ?: "未知错误"}"
                    )
                }
                val validated = runCatching {
                    BackupPayloadValidator.validate(backup.toRestorePayload())
                }.getOrElse { error ->
                    return@runCatching LocalBackupPreviewResult(
                        false,
                        "备份校验失败：${error.message ?: "未知错误"}"
                    )
                }
                val preview = LocalBackupPreview(
                    version = backup.version,
                    exportTime = backup.exportTime,
                    appVersionName = backup.appVersionName,
                    semesterNames = validated.semesters.map { it.name },
                    semesterCount = validated.semesters.size,
                    courseCount = validated.courses.size,
                    profileCount = validated.profiles.size,
                )
                LocalBackupPreviewResult(true, "已读取备份信息", preview)
            }.getOrElse { error ->
                LocalBackupPreviewResult(false, "读取备份失败：${error.message ?: "未知错误"}")
            }
        }
    }
}
