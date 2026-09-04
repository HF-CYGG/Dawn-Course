package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncSourceBinding
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.WebDavBackup
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 在任何破坏性数据库操作前使用的统一备份快照。 */
internal data class BackupRestorePayload(
    val version: Int,
    val timestamp: Long,
    val settings: AppSettings?,
    val semesters: List<Semester>?,
    val courses: List<Course>?,
    val selectedSemesterId: Long?,
    val profiles: List<TimetableProfile>? = null,
    val sourceBindings: List<SyncSourceBinding>? = null,
    val activeProfileId: Long? = null,
)

/** 已完成全部输入校验和选择解析、可以安全提交的数据。 */
internal data class ValidatedBackupRestore(
    val settings: AppSettings,
    val semesters: List<Semester>,
    val courses: List<Course>,
    /** 兼容当前 UI 的已解析活动学期。 */
    val selectedSemesterId: Long?,
    val profiles: List<TimetableProfile> = emptyList(),
    val sourceBindings: List<SyncSourceBinding> = emptyList(),
    val activeProfileId: Long? = null,
)

/** Local Backup 与 WebDAV 共用的 fail-closed 校验器。 */
internal object BackupPayloadValidator {
    private const val MIN_SUPPORTED_VERSION = 1
    private const val MAX_SUPPORTED_VERSION = 4

    fun validate(payload: BackupRestorePayload): ValidatedBackupRestore {
        require(payload.version in MIN_SUPPORTED_VERSION..MAX_SUPPORTED_VERSION) {
            "不支持的备份版本：${payload.version}"
        }
        require(payload.timestamp > 0L) { "备份缺少有效时间戳" }

        val settings = requireNotNull(payload.settings) { "备份缺少设置数据" }
        val rawSemesters = requireNotNull(payload.semesters) { "备份缺少学期列表" }
        val courses = requireNotNull(payload.courses) { "备份缺少课程列表" }
        require(rawSemesters.isNotEmpty() || courses.isNotEmpty() || !payload.profiles.isNullOrEmpty()) {
            "备份内容为空"
        }
        validateSettingsStructure(settings)

        val legacySelectedSemesterId = if (payload.version <= 2) {
            BackupSemesterSelectionResolver.resolve(
                version = payload.version,
                requestedSemesterId = payload.selectedSemesterId,
                semesters = rawSemesters,
            )
        } else {
            require(payload.selectedSemesterId == null) { "v3/v4 不得携带 selectedSemesterId" }
            null
        }

        val (profiles, semesters, activeProfileId) = if (payload.version <= 2) {
            require(payload.profiles == null) { "v1/v2 备份不得携带 profiles" }
            require(payload.activeProfileId == null) { "v1/v2 备份不得携带 activeProfileId" }
            val legacyProfile = TimetableProfile(
                id = LEGACY_PROFILE_ID,
                uuid = legacyProfileUuid(payload.timestamp),
                name = "导入的旧版课表",
                activeSemesterId = legacySelectedSemesterId,
            )
            Triple(
                listOf(legacyProfile),
                rawSemesters.map { it.copy(profileId = LEGACY_PROFILE_ID) },
                LEGACY_PROFILE_ID,
            )
        } else {
            val versionProfiles = requireNotNull(payload.profiles) { "v3+ 备份缺少 Profile 列表" }
            val requestedActiveProfileId = requireNotNull(payload.activeProfileId) {
                "v3+ 备份缺少 activeProfileId"
            }
            require(requestedActiveProfileId >= 0L) { "activeProfileId 不能为负数" }
            Triple(versionProfiles, rawSemesters, requestedActiveProfileId.takeIf { it > 0L })
        }

        val profileIds = profiles.map { profile ->
            require(profile.id > 0L) { "Profile ID 必须为正数" }
            require(profile.name.isNotBlank()) { "Profile 名称不能为空" }
            require(runCatching { UUID.fromString(profile.uuid) }.isSuccess) { "Profile UUID 无效" }
            require(profile.sortOrder >= 0) { "Profile 排序值不能为负数" }
            profile.id
        }
        require(profileIds.distinct().size == profileIds.size) { "备份包含重复 Profile ID" }
        require(profiles.map { it.uuid }.distinct().size == profiles.size) { "备份包含重复 Profile UUID" }
        val profileById = profiles.associateBy { it.id }
        require(activeProfileId == null || activeProfileId in profileById) {
            "activeProfileId 引用了不存在的 Profile"
        }

        val semesterIds = semesters.map { semester ->
            require(semester.id > 0L) { "学期 ID 必须为正数" }
            require(semester.profileId in profileById) { "学期引用了不存在的 Profile" }
            require(semester.name.isNotBlank()) { "学期名称不能为空" }
            require(semester.weekCount > 0) { "学期周数必须为正数" }
            semester.id
        }
        require(semesterIds.distinct().size == semesterIds.size) { "备份包含重复学期 ID" }
        val semesterById = semesters.associateBy { it.id }
        profiles.forEach { profile ->
            val activeSemester = profile.activeSemesterId ?: return@forEach
            require(semesterById[activeSemester]?.profileId == profile.id) {
                "Profile.activeSemesterId 引用了其他 Profile 或不存在的学期"
            }
        }

        val courseIds = courses.map { course ->
            require(course.id > 0L) { "课程 ID 必须为正数" }
            require(course.originId >= 0L) { "课程 originId 不能为负数" }
            require(course.name.isNotBlank()) { "课程名称不能为空" }
            require(course.semesterId in semesterById) { "课程引用了不存在的学期" }
            require(course.dayOfWeek in 1..7) { "课程星期超出范围" }
            require(course.startSection > 0 && course.duration > 0) { "课程节次无效" }
            val semester = semesterById.getValue(course.semesterId)
            require(
                course.startWeek > 0 &&
                    course.endWeek >= course.startWeek &&
                    course.endWeek <= semester.weekCount
            ) { "课程周次超出所属学期范围" }
            require(course.weekType in Course.WEEK_TYPE_ALL..Course.WEEK_TYPE_EVEN) {
                "课程单双周类型无效"
            }
            course.id
        }
        require(courseIds.distinct().size == courseIds.size) { "备份包含重复课程 ID" }
        val courseBusinessKeys = courses.map { course ->
            CourseBusinessKey(
                semesterId = course.semesterId,
                name = course.name,
                dayOfWeek = course.dayOfWeek,
                startSection = course.startSection,
                duration = course.duration,
                startWeek = course.startWeek,
                endWeek = course.endWeek,
                weekType = course.weekType,
                originId = course.originId,
                isModified = course.isModified,
            )
        }
        require(courseBusinessKeys.distinct().size == courseBusinessKeys.size) {
            "备份包含重复课程业务键"
        }
        val courseById = courses.associateBy { it.id }
        courses.asSequence()
            .filter { course -> course.originId > 0L }
            .groupBy { course -> course.originId }
            .forEach { (originId, family) ->
                val familySemesterIds = family.mapTo(linkedSetOf()) { course -> course.semesterId }
                courseById[originId]?.let { anchor -> familySemesterIds += anchor.semesterId }
                require(familySemesterIds.size == 1) {
                    "课程 originId 跨越了学期或 Profile 边界"
                }
            }

        val sourceBindings = when {
            payload.version <= 3 -> {
                require(payload.sourceBindings == null) { "v1-v3 备份不得携带 sourceBindings" }
                emptyList()
            }
            else -> requireNotNull(payload.sourceBindings) { "v4 备份缺少 sourceBindings" }
        }
        val bindingIds = HashSet<String>()
        val boundSemesters = HashSet<Long>()
        sourceBindings.forEach { binding ->
            require(binding.sourceBindingId.isNotBlank() && bindingIds.add(binding.sourceBindingId)) {
                "备份包含无效或重复 sourceBindingId"
            }
            require(boundSemesters.add(binding.semesterId)) { "同一学期存在多个来源绑定" }
            binding.provider.name
            require(binding.profileId in profileById) { "来源绑定引用了不存在的 Profile" }
            require(semesterById[binding.semesterId]?.profileId == binding.profileId) {
                "来源绑定的 Profile/学期归属不一致"
            }
        }

        when (payload.version) {
            1 -> require(payload.selectedSemesterId == null) {
                "v1 备份不得携带 selectedSemesterId"
            }
            2 -> {
                val selected = requireNotNull(payload.selectedSemesterId) {
                    "v2 备份缺少 selectedSemesterId"
                }
                require(selected >= 0L) { "selectedSemesterId 不能为负数" }
                require(selected == 0L || selected in semesterById) {
                    "selectedSemesterId 引用了不存在的学期"
                }
            }
            3, 4 -> Unit
        }

        val resolvedSelectedSemesterId = if (payload.version <= 2) {
            legacySelectedSemesterId
        } else {
            activeProfileId?.let(profileById::get)?.activeSemesterId
        }

        return ValidatedBackupRestore(
            settings = settings,
            semesters = semesters,
            courses = courses,
            selectedSemesterId = resolvedSelectedSemesterId,
            profiles = profiles,
            sourceBindings = sourceBindings,
            activeProfileId = activeProfileId,
        )
    }

    /** 预先触达恢复阶段会读取的所有非空复合字段，防止 Gson 绕过 Kotlin 空安全。 */
    private fun validateSettingsStructure(settings: AppSettings) {
        settings.fontStyle.name
        settings.dividerType.name
        settings.wallpaperMode.name
        settings.themeMode.name
        settings.webDavAutoSyncMode.name
        settings.webDavAutoSyncIntervalUnit.name
        settings.dividerColor.length
        settings.sectionTimes.forEach { sectionTime ->
            require(sectionTime.startTime.isNotBlank() && sectionTime.endTime.isNotBlank()) {
                "节次时间不能为空"
            }
        }
        require(settings.transparency.isFinite()) { "透明度无效" }
        require(settings.dividerWidthDp.isFinite()) { "分割线宽度无效" }
        require(settings.dividerAlpha.isFinite()) { "分割线透明度无效" }
        require(settings.cardAlpha.isFinite()) { "卡片透明度无效" }
        require(settings.backgroundBlur.isFinite()) { "背景模糊值无效" }
        require(settings.backgroundBrightness.isFinite()) { "背景亮度无效" }
    }

    private fun legacyProfileUuid(timestamp: Long): String = UUID.nameUUIDFromBytes(
        "dawn-course-legacy-backup:$timestamp".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    private const val LEGACY_PROFILE_ID = 1L

    /** 必须与 CourseEntity.index_courses_dedupe 的列顺序保持同一业务语义。 */
    private data class CourseBusinessKey(
        val semesterId: Long,
        val name: String,
        val dayOfWeek: Int,
        val startSection: Int,
        val duration: Int,
        val startWeek: Int,
        val endWeek: Int,
        val weekType: Int,
        val originId: Long,
        val isModified: Boolean,
    )
}

/** 先完整验证并解析，再允许调用方进入 Room 替换事务。 */
internal object BackupRestoreGate {
    suspend fun validateThenCommit(
        payload: BackupRestorePayload,
        commit: suspend (ValidatedBackupRestore) -> Unit
    ): Result<ValidatedBackupRestore> {
        val validated = try {
            BackupPayloadValidator.validate(payload)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        return try {
            commit(validated)
            Result.success(validated)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}

internal fun LocalBackupData.toRestorePayload() = BackupRestorePayload(
    version = version,
    timestamp = exportTime,
    settings = settings,
    semesters = semesters,
    courses = courses,
    selectedSemesterId = selectedSemesterId,
    profiles = profiles,
    sourceBindings = sourceBindings,
    activeProfileId = activeProfileId,
)

internal fun WebDavBackup.toRestorePayload() = BackupRestorePayload(
    version = version,
    timestamp = lastModified,
    settings = settings,
    semesters = semesters,
    courses = courses,
    selectedSemesterId = selectedSemesterId,
    profiles = profiles,
    sourceBindings = sourceBindings,
    activeProfileId = activeProfileId,
)
