package com.dawncourse.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.LocalBackupResult
import com.dawncourse.core.domain.repository.LocalBackupRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本地备份仓库实现
 *
 * 使用 JSON + SAF 进行备份与还原：
 * - 备份时读取数据库与设置快照，序列化后写入 URI
 * - 还原时解析 JSON 并在事务中覆盖数据库，再恢复设置
 */
@Singleton
class LocalBackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) : LocalBackupRepository {

    /** JSON 序列化工具 */
    private val gson = GsonBuilder().create()

    /**
     * 导出备份到 SAF 文件
     */
    override suspend fun exportToUri(uri: String): LocalBackupResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val parsedUri = Uri.parse(uri)
                // 读取设置与数据库快照
                val settings = settingsRepository.settings.first()
                val semesters = database.semesterDao().getAllSemestersOnce().map { it.toDomain() }
                val courses = database.courseDao().getAllCoursesOnce().map { it.toDomain() }
                // 获取应用版本号用于写入元数据
                val versionName = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull().orEmpty()
                // 组装备份对象并序列化为 JSON
                val backup = LocalBackupData(
                    exportTime = System.currentTimeMillis(),
                    appVersionName = versionName,
                    settings = settings,
                    semesters = semesters,
                    courses = courses
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
                val parsedUri = Uri.parse(uri)
                // 读取 JSON 文本
                val inputStream = context.contentResolver.openInputStream(parsedUri)
                if (inputStream == null) {
                    return@runCatching LocalBackupResult(false, "无法读取备份文件")
                }
                val json = inputStream.use { it.bufferedReader().readText() }
                // 解析 JSON
                val backup = gson.fromJson(json, LocalBackupData::class.java)
                // 版本校验，避免低版本应用强行还原高版本备份
                if (backup.version > LocalBackupData.CURRENT_VERSION) {
                    return@runCatching LocalBackupResult(false, "备份文件版本过高，请先升级应用")
                }
                // 数据库替换使用事务，确保原子性
                database.withTransaction {
                    database.courseDao().deleteAllCourses()
                    database.semesterDao().deleteAllSemesters()
                    backup.semesters.forEach { database.semesterDao().insertSemester(it.toEntity()) }
                    backup.courses.forEach { database.courseDao().insertCourse(it.toEntity()) }
                }
                // 恢复设置快照
                settingsRepository.setAllSettings(backup.settings)
                LocalBackupResult(true, "已完成数据还原")
            }.getOrElse { error ->
                LocalBackupResult(false, "还原失败：${error.message ?: "未知错误"}")
            }
        }
    }
}
