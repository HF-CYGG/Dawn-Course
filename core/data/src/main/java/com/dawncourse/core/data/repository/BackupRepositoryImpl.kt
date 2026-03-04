package com.dawncourse.core.data.repository

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.BackupConstants
import com.dawncourse.core.domain.model.BackupFile
import com.dawncourse.core.domain.model.BackupMeta
import com.dawncourse.core.domain.model.BackupPayload
import com.dawncourse.core.domain.model.BackupResult
import com.dawncourse.core.domain.model.BackupSummary
import com.dawncourse.core.domain.model.RestoreResult
import com.dawncourse.core.domain.repository.BackupRepository
import com.dawncourse.core.domain.repository.SettingsRepository
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject

/**
 * 备份与还原仓库实现
 */
class BackupRepositoryImpl @Inject constructor(
    /** 数据库实例 */
    private val database: AppDatabase,
    /** 课程数据访问对象 */
    private val courseDao: CourseDao,
    /** 学期数据访问对象 */
    private val semesterDao: SemesterDao,
    /** 设置仓库 */
    private val settingsRepository: SettingsRepository,
    /** 应用上下文 */
    @ApplicationContext private val context: Context
) : BackupRepository {

    /** JSON 序列化工具 */
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * 导出备份
     *
     * @param outputStream 输出流
     */
    override suspend fun exportBackup(outputStream: OutputStream): BackupResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val semesters = semesterDao.getAllSemestersOnce().map { it.toDomain() }
                val courses = courseDao.getAllCoursesOnce().map { it.toDomain() }
                val settings = settingsRepository.settings.first()

                val payload = BackupPayload(
                    semesters = semesters,
                    courses = courses,
                    settings = settings
                )
                val payloadJson = gson.toJson(payload)
                val checksum = sha256(payloadJson)
                val meta = BackupMeta(
                    schemaVersion = BackupConstants.SCHEMA_VERSION,
                    appVersionName = getAppVersionName(),
                    appVersionCode = getAppVersionCode(),
                    exportedAt = System.currentTimeMillis(),
                    deviceId = getDeviceId(),
                    checksum = checksum
                )
                val backupFile = BackupFile(meta = meta, payload = payload)
                val json = gson.toJson(backupFile)
                outputStream.use { it.write(json.toByteArray()) }

                BackupResult.Success(
                    BackupSummary(
                        semesterCount = semesters.size,
                        courseCount = courses.size
                    )
                )
            }.getOrElse { BackupResult.Failure(it.message ?: "备份失败") }
        }
    }

    /**
     * 导入备份
     *
     * @param inputStream 输入流
     */
    override suspend fun importBackup(inputStream: InputStream): RestoreResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val json = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val backupFile = gson.fromJson(json, BackupFile::class.java)
                    ?: return@runCatching RestoreResult.Failure("备份文件解析失败")

                if (backupFile.meta.schemaVersion > BackupConstants.SCHEMA_VERSION) {
                    return@runCatching RestoreResult.Failure("备份版本过新，当前应用无法导入")
                }

                val payloadJson = gson.toJson(backupFile.payload)
                val checksum = sha256(payloadJson)
                val expected = backupFile.meta.checksum
                if (!expected.isNullOrBlank() && expected != checksum) {
                    return@runCatching RestoreResult.Failure("备份校验失败，文件可能已损坏")
                }

                database.withTransaction {
                    courseDao.clearAllCourses()
                    semesterDao.clearAllSemesters()

                    val semesterEntities = backupFile.payload.semesters.map { it.toEntity() }
                    val courseEntities = backupFile.payload.courses.map { it.toEntity() }

                    semesterEntities.forEach { semesterDao.insertSemester(it) }
                    if (courseEntities.isNotEmpty()) {
                        courseDao.insertCourses(courseEntities)
                    }
                }

                applySettings(backupFile.payload.settings)

                RestoreResult.Success(
                    BackupSummary(
                        semesterCount = backupFile.payload.semesters.size,
                        courseCount = backupFile.payload.courses.size
                    )
                )
            }.getOrElse { RestoreResult.Failure(it.message ?: "还原失败") }
        }
    }

    /**
     * 应用设置快照
     *
     * @param settings 设置数据
     */
    private suspend fun applySettings(settings: AppSettings) {
        settingsRepository.setDynamicColor(settings.dynamicColor)
        settingsRepository.setWallpaperUri(settings.wallpaperUri)
        settingsRepository.generateBlurredWallpaper(settings.wallpaperUri)
        settingsRepository.setTransparency(settings.transparency)
        settingsRepository.setFontStyle(settings.fontStyle)
        settingsRepository.setDividerType(settings.dividerType)
        settingsRepository.setDividerWidth(settings.dividerWidthDp)
        settingsRepository.setDividerColor(settings.dividerColor)
        settingsRepository.setDividerAlpha(settings.dividerAlpha)
        settingsRepository.setCourseItemHeight(settings.courseItemHeightDp)
        settingsRepository.setMaxDailySections(settings.maxDailySections)
        settingsRepository.setDefaultCourseDuration(settings.defaultCourseDuration)
        settingsRepository.setSectionTimes(settings.sectionTimes)
        settingsRepository.setCardCornerRadius(settings.cardCornerRadius)
        settingsRepository.setCardAlpha(settings.cardAlpha)
        settingsRepository.setShowCourseIcons(settings.showCourseIcons)
        settingsRepository.setWallpaperMode(settings.wallpaperMode)
        settingsRepository.setThemeMode(settings.themeMode)
        settingsRepository.setShowWeekend(settings.showWeekend)
        settingsRepository.setShowSidebarTime(settings.showSidebarTime)
        settingsRepository.setShowSidebarIndex(settings.showSidebarIndex)
        settingsRepository.setHideNonThisWeek(settings.hideNonThisWeek)
        settingsRepository.setShowDateInHeader(settings.showDateInHeader)
        settingsRepository.setCurrentSemesterName(settings.currentSemesterName)
        settingsRepository.setTotalWeeks(settings.totalWeeks)
        settingsRepository.setStartDateTimestamp(settings.startDateTimestamp)
        settingsRepository.setEnableClassReminder(settings.enableClassReminder)
        settingsRepository.setReminderMinutes(settings.reminderMinutes)
        settingsRepository.setEnablePersistentNotification(settings.enablePersistentNotification)
        settingsRepository.setEnableAutoMute(settings.enableAutoMute)
        settingsRepository.setIgnoredUpdateVersion(settings.ignoredUpdateVersion)
        settingsRepository.setBlurredWallpaperUri(settings.blurredWallpaperUri)
        settingsRepository.setBackgroundBlur(settings.backgroundBlur)
        settingsRepository.setBackgroundBrightness(settings.backgroundBrightness)
    }

    /**
     * 获取设备标识
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    /**
     * 获取应用版本名
     */
    private fun getAppVersionName(): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    /**
     * 获取应用版本号
     */
    private fun getAppVersionCode(): Int {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                // 低版本系统仍需使用已废弃字段
                info.versionCode
            }
        }.getOrDefault(0)
    }

    /**
     * 计算 SHA-256 校验值
     *
     * @param value 原始字符串
     */
    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }
}
