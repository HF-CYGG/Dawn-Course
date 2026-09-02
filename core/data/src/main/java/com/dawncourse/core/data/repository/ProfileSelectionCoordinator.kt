package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.startup.DawnStartupTrace
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.dao.SyncSourceBindingDao
import com.dawncourse.core.data.local.dao.TimetableProfileDao
import com.dawncourse.core.data.local.entity.SemesterEntity
import com.dawncourse.core.data.local.entity.SyncSourceBindingEntity
import com.dawncourse.core.data.local.entity.TimetableProfileEntity
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.model.ProfileCreationRequest
import com.dawncourse.core.domain.model.ProfileDeletionImpact
import com.dawncourse.core.domain.model.ProfileMutationResult
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.SyncCredentials
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.TimetableProfileSummary
import com.dawncourse.core.domain.repository.CredentialBindingMutationResult
import com.dawncourse.core.domain.repository.CredentialsRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 串行化 Profile、学期选择与跨存储补偿，确保运行时只有一套选择事实。 */
@Singleton
class ProfileSelectionCoordinator @Inject constructor(
    private val database: AppDatabase,
    private val profileDao: TimetableProfileDao,
    private val semesterDao: SemesterDao,
    private val courseDao: CourseDao,
    private val bindingDao: SyncSourceBindingDao,
    private val activeSelectionStore: ActiveProfileSelectionStore,
    private val legacySemesterSelectionStore: SemesterSelectionStore,
    private val credentialsRepository: CredentialsRepository,
    private val mutationGate: OperationalDataMutationGate,
) {
    private val mutationMutex = Mutex()

    /**
     * 供同一 data 模块的复合写入协调器复用 Profile 选择锁。
     *
     * 导入、自动更新等操作必须与切换课表串行，避免“新课表 + 旧课程”的瞬时组合。
     */
    internal suspend fun <T> withProfileMutationLock(block: suspend () -> T): T =
        mutationMutex.withLock { block() }

    /**
     * 将“门状态检查 + Profile 选择锁 + Room 事务”放在一个线性化区间。
     *
     * [withProfileMutationLock] 仅供已持有 [OperationalDataMutationGate.Lease] 的恢复协议或
     * 已从外层取得 gate 的导入提交复用，普通公开写入口必须使用本方法。
     */
    private suspend fun <T> withOperationalProfileMutation(block: suspend () -> T): T =
        mutationGate.withMutation {
            mutationMutex.withLock { block() }
        }

    /**
     * 在 Profile 选择锁内取得并消费稳定上下文。
     *
     * 导出必须让活动 Profile 的解析与 Room 快照处于同一线性化区间，
     * 否则切换课表可能产生“Profile A 的选择 + Profile B 的数据”备份。
     */
    internal suspend fun <T> withResolvedActiveContext(
        block: suspend (ActiveTimetableContext) -> T,
    ): T = withOperationalProfileMutation {
        val profileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        val profile = profileDao.getProfileById(profileId)
            ?: throw IllegalStateException("无法解析活动课表")
        block(success(profile, validSemester(profile)).activeContext)
    }

    /** 首次仅从遗留学期键桥接；之后无效 ID 稳定回退到排序第一项。 */
    fun observeActiveContext(): Flow<ActiveTimetableContext?> = flow {
        withOperationalProfileMutation {
            resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        }
        emitAll(
            activeSelectionStore.activeProfileId.flatMapLatest { profileId ->
                profileId?.let(profileDao::observeProfileById)?.flatMapLatest { profile ->
                    if (profile == null) flowOf(null) else {
                        profile.activeSemesterId?.let(semesterDao::observeSemesterById)?.map { semester ->
                            val valid = semester?.takeIf { it.profileId == profile.id }
                            ActiveTimetableContext(profile.toDomain(), valid?.toDomain())
                        } ?: flowOf(ActiveTimetableContext(profile.toDomain()))
                    }
                } ?: flowOf(null)
            },
        )
    }

    fun observeProfiles(): Flow<List<TimetableProfile>> = flow {
        withOperationalProfileMutation {
            resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        }
        emitAll(profileDao.observeAllProfiles().map { rows -> rows.map { it.toDomain() } })
    }

    fun observeSemesters(profileId: Long): Flow<List<Semester>> =
        semesterDao.getSemestersByProfile(profileId).map { rows -> rows.map { it.toDomain() } }

    fun observeProfileSummaries(): Flow<List<TimetableProfileSummary>> = flow {
        withOperationalProfileMutation {
            resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        }
        emitAll(
            combine(
                profileDao.observeAllProfiles(), semesterDao.getAllSemesters(),
                courseDao.getAllCourses(), activeSelectionStore.activeProfileId,
            ) { profiles, semesters, courses, activeId ->
                val semesterById = semesters.associateBy { it.id }
                val profileBySemester = semesters.associate { it.id to it.profileId }
                profiles.map { profile ->
                    TimetableProfileSummary(
                        profile = profile.toDomain(),
                        activeSemester = profile.activeSemesterId?.let(semesterById::get)
                            ?.takeIf { it.profileId == profile.id }?.toDomain(),
                        semesterCount = semesters.count { it.profileId == profile.id },
                        courseCount = courses.count { profileBySemester[it.semesterId] == profile.id },
                        isActive = profile.id == activeId,
                    )
                }
            },
        )
    }

    suspend fun switch(profileId: Long): ProfileMutationResult = mutate {
        val target = profileDao.getProfileById(profileId) ?: reject("目标课表不存在")
        resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        val selectionPreimage = activeSelectionStore.rawActiveProfileId.first()
        try {
            activeSelectionStore.selectProfile(target.id)
        } catch (failure: Throwable) {
            restoreSelectionOrFail(
                selectionPreimage = selectionPreimage,
                originalFailure = failure,
                inconsistentMessage = "课表选择写入失败且无法恢复原选择",
            )
            if (failure is CancellationException) throw failure
            reject("切换课表失败：选择存储不可用")
        }
        val usedAt = System.currentTimeMillis()
        try {
            database.withTransaction { profileDao.updateLastUsedAt(target.id, usedAt) }
        } catch (failure: Throwable) {
            restoreSelectionOrFail(
                selectionPreimage = selectionPreimage,
                originalFailure = failure,
                inconsistentMessage = "课表选择已变化且无法恢复原选择",
            )
            throw failure
        }
        val updated = target.copy(lastUsedAt = usedAt)
        success(updated, validSemester(updated))
    }

    suspend fun setActiveSemester(profileId: Long, semesterId: Long?): ProfileMutationResult = mutate {
        val updated = database.withTransaction {
            val profile = profileDao.getProfileById(profileId) ?: reject("课表不存在")
            val semester = semesterId?.let { semesterDao.getSemesterById(it) }
            if (semesterId != null && (semester == null || semester.profileId != profileId)) {
                reject("学期不属于目标课表")
            }
            profileDao.updateActiveSemesterId(profileId, semesterId)
            profile.copy(activeSemesterId = semesterId) to semester
        }
        success(updated.first, updated.second)
    }

    suspend fun create(request: ProfileCreationRequest): ProfileMutationResult = mutate {
        val name = request.name.trim().takeIf { it.isNotEmpty() } ?: reject("课表名称不能为空")
        val selectionPreimage = activeSelectionStore.rawActiveProfileId.first()
        val created = database.withTransaction {
            when (request) {
                is ProfileCreationRequest.Empty -> createEmptyLocked(name)
                is ProfileCreationRequest.WithSemester -> createWithSemesterLocked(name, request.semester)
                is ProfileCreationRequest.Clone -> createCloneLocked(name, request.sourceProfileId)
            }
        }
        try {
            if (request is ProfileCreationRequest.Clone) {
                credentialsRepository.copyCredentials(request.sourceProfileId, created.first.id)
            }
            activeSelectionStore.selectProfile(created.first.id)
        } catch (failure: Throwable) {
            val rollbackFailure = compensateCreatedProfile(
                profileId = created.first.id,
                selectionPreimage = selectionPreimage,
            )
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure)
                throw ConsistencyFailure("课表创建未完成且自动回滚失败", failure)
            }
            if (failure is CancellationException) throw failure
            reject("创建课表失败：外部存储写入未完成")
        }
        success(created.first, created.second)
    }

    suspend fun createSemester(profileId: Long, spec: NewSemesterSpec): ProfileMutationResult = mutate {
        val created = database.withTransaction {
            val profile = profileDao.getProfileById(profileId) ?: reject("课表不存在")
            validateSemesterSpec(spec)
            val semester = SemesterEntity(
                profileId = profileId, name = spec.name.trim(),
                startDate = spec.startDate, weekCount = spec.weekCount,
            )
            val id = semesterDao.insertSemester(semester)
            profileDao.updateActiveSemesterId(profileId, id)
            profile.copy(activeSemesterId = id) to semester.copy(id = id)
        }
        success(created.first, created.second)
    }

    suspend fun rename(profileId: Long, name: String): ProfileMutationResult = mutate {
        val normalized = name.trim().takeIf { it.isNotEmpty() } ?: reject("课表名称不能为空")
        val updated = database.withTransaction {
            val profile = profileDao.getProfileById(profileId) ?: reject("课表不存在")
            profileDao.updateName(profileId, normalized)
            profile.copy(name = normalized)
        }
        success(updated, validSemester(updated))
    }

    suspend fun previewDeletion(profileId: Long): ProfileDeletionImpact? = withOperationalProfileMutation {
        resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        val profile = profileDao.getProfileById(profileId) ?: return@withOperationalProfileMutation null
        ProfileDeletionImpact(
            profileId = profileId, profileName = profile.name,
            semesterCount = semesterDao.countByProfile(profileId),
            courseCount = courseDao.countByProfile(profileId),
            sourceBindingCount = bindingDao.countByProfile(profileId),
            credentialCount = if (credentialsRepository.getCredentials(profileId) == null) 0 else 1,
            isActive = activeSelectionStore.activeProfileId.first() == profileId,
            remainingProfileCount = (profileDao.countProfiles() - 1).coerceAtLeast(0),
        )
    }

    suspend fun delete(profileId: Long): ProfileMutationResult = mutate {
        val profiles = profileDao.getAllProfilesOnce()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) reject("课表不存在")
        if (profiles.size <= 1) reject("至少保留一套课表")
        val currentId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        val selectionPreimage = activeSelectionStore.rawActiveProfileId.first()
        val wasActive = currentId == profileId
        val fallback = profiles.getOrNull(index + 1) ?: profiles.getOrNull(index - 1) ?: profiles.minBy { it.id }
        if (wasActive) {
            try {
                activeSelectionStore.selectProfile(fallback.id)
            } catch (failure: Throwable) {
                restoreSelectionOrFail(
                    selectionPreimage = selectionPreimage,
                    originalFailure = failure,
                    inconsistentMessage = "删除课表的回退选择失败且无法恢复",
                )
                if (failure is CancellationException) throw failure
                reject("删除课表失败：无法写入稳定回退选择")
            }
        }
        try {
            database.withTransaction { deleteAggregateLocked(profileId) }
        } catch (failure: Throwable) {
            if (wasActive) {
                restoreSelectionOrFail(
                    selectionPreimage = selectionPreimage,
                    originalFailure = failure,
                    inconsistentMessage = "课表删除未提交且无法恢复原选择",
                )
            }
            throw failure
        }
        val active = if (wasActive) fallback else profileDao.getProfileById(currentId) ?: fallback
        try {
            withContext(NonCancellable) { credentialsRepository.clearCredentials(profileId) }
        } catch (failure: Exception) {
            return@mutate ProfileMutationResult.Inconsistent(
                reason = "课表数据已删除，但关联凭据清理失败",
                activeContext = success(active, validSemester(active)).activeContext,
            )
        }
        success(active, validSemester(active))
    }

    /** SemesterRepository 的全部写操作复用同一把锁，且绝不写 selected_semester_id。 */
    suspend fun insertSemester(semester: Semester): Long = withOperationalProfileMutation {
        require(semester.profileId > 0L) { "学期必须显式指定 Profile" }
        database.withTransaction {
            val profile = profileDao.getProfileById(semester.profileId)
                ?: throw IllegalArgumentException("课表不存在")
            val id = semesterDao.insertSemester(semester.copy(isCurrent = false).toEntity())
            if (semester.isCurrent) profileDao.updateActiveSemesterId(profile.id, id)
            id
        }
    }

    suspend fun updateSemester(semester: Semester) = withOperationalProfileMutation {
        require(semester.profileId > 0L) { "学期必须显式指定 Profile" }
        database.withTransaction {
            val existing = semesterDao.getSemesterById(semester.id)
                ?: throw IllegalArgumentException("学期不存在")
            require(existing.profileId == semester.profileId) { "禁止跨 Profile 移动学期" }
            semesterDao.updateSemester(semester.copy(isCurrent = false).toEntity())
        }
    }

    suspend fun deleteSemester(semester: Semester) = withOperationalProfileMutation {
        database.withTransaction {
            val existing = semesterDao.getSemesterById(semester.id)
                ?: throw IllegalArgumentException("学期不存在")
            require(existing.profileId == semester.profileId) { "学期不属于目标 Profile" }
            val profile = profileDao.getProfileById(existing.profileId)
                ?: throw IllegalArgumentException("课表不存在")
            if (profile.activeSemesterId == existing.id) profileDao.updateActiveSemesterId(profile.id, null)
            semesterDao.deleteCoursesForSemester(existing.id)
            semesterDao.deleteSemester(existing)
        }
    }

    suspend fun deleteAllSemesters() = withOperationalProfileMutation {
        database.withTransaction {
            profileDao.clearAllActiveSemesterIds()
            bindingDao.deleteAll()
            courseDao.deleteAllCourses()
            semesterDao.deleteAllSemesters()
        }
    }

    suspend fun setCurrentSemester(id: Long) = withOperationalProfileMutation {
        val activeProfileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        database.withTransaction {
            val semester = semesterDao.getSemesterById(id) ?: return@withTransaction
            require(semester.profileId == activeProfileId) { "禁止通过兼容入口修改非活动 Profile" }
            profileDao.updateActiveSemesterId(semester.profileId, semester.id)
        }
    }

    /**
     * 将“活动 Profile/学期复核”与调用方的 Room 写入放进同一选择锁和事务，
     * 消除 UI 预检查与实际课程提交之间的 Profile 切换窗口。
     */
    internal suspend fun <T> withActiveScopeTransaction(
        profileId: Long,
        semesterId: Long,
        block: suspend () -> T,
    ): T? = withOperationalProfileMutation {
        val activeProfileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        if (activeProfileId != profileId) return@withOperationalProfileMutation null
        database.withTransaction {
            val profile = profileDao.getProfileById(profileId) ?: return@withTransaction null
            val semester = semesterDao.getSemesterById(semesterId) ?: return@withTransaction null
            if (profile.activeSemesterId != semesterId || semester.profileId != profileId) {
                return@withTransaction null
            }
            block()
        }
    }

    /** 与 Profile 变更复用同一把锁，保证 Receiver 的最终副作用不会跨越切换边界。 */
    internal suspend fun <T> executeActiveAction(
        profileId: Long,
        semesterId: Long,
        action: suspend (ActiveTimetableContext) -> T,
    ): T? = withOperationalProfileMutation {
        val activeProfileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        if (activeProfileId != profileId) return@withOperationalProfileMutation null
        val profile = profileDao.getProfileById(profileId) ?: return@withOperationalProfileMutation null
        val semester = semesterDao.getSemesterById(semesterId) ?: return@withOperationalProfileMutation null
        if (profile.activeSemesterId != semesterId || semester.profileId != profileId) {
            return@withOperationalProfileMutation null
        }
        action(success(profile, semester).activeContext)
    }

    /**
     * 为活动 Profile 的当前学期创建或复用唯一来源绑定。
     *
     * 旧版本只有 Profile 凭据而没有绑定记录；首次同步补齐 UUID，后续网络提交均必须
     * 携带该 UUID，在提交处严格复核，不能让 null 成为绕过路径。
     */
    suspend fun ensureSourceBindingIfStillActive(
        profileId: Long,
        semesterId: Long,
        provider: SyncProviderType,
    ): String? = withOperationalProfileMutation {
        val activeProfileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        if (activeProfileId != profileId) return@withOperationalProfileMutation null
        database.withTransaction {
            val profile = profileDao.getProfileById(profileId) ?: return@withTransaction null
            val semester = semesterDao.getSemesterById(semesterId) ?: return@withTransaction null
            if (profile.activeSemesterId != semesterId || semester.profileId != profileId) {
                return@withTransaction null
            }
            val existing = bindingDao.getBySemesterOnce(semesterId)
            when {
                existing == null -> {
                    val now = System.currentTimeMillis()
                    val binding = SyncSourceBindingEntity(
                        sourceBindingId = UUID.randomUUID().toString(),
                        profileId = profileId,
                        semesterId = semesterId,
                        provider = provider.name,
                        createdAt = now,
                        updatedAt = now,
                    )
                    bindingDao.insert(binding)
                    binding.sourceBindingId
                }
                existing.profileId == profileId && existing.semesterId == semesterId &&
                    existing.provider == provider.name -> existing.sourceBindingId
                else -> null
            }
        }
    }

    /** 在选择锁内完成凭据文件写入与 Room binding 重绑，旧 binding UUID 会立即失效。 */
    suspend fun saveCredentialsAndRebindIfActive(
        profileId: Long,
        credentials: SyncCredentials,
    ): CredentialBindingMutationResult = withOperationalProfileMutation {
        val activeProfileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        if (activeProfileId != profileId) {
            return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("活动课表已变化")
        }
        val profile = profileDao.getProfileById(profileId)
            ?: return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("课表不存在")
        val semester = profile.activeSemesterId?.let { semesterDao.getSemesterById(it) }
        if (profile.activeSemesterId != null && semester?.profileId != profileId) {
            return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("活动学期不属于目标课表")
        }
        val oldBinding = semester?.let { bindingDao.getBySemesterOnce(it.id) }
        if (oldBinding != null &&
            (oldBinding.profileId != profileId || oldBinding.semesterId != semester.id)
        ) {
            return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("同步来源绑定归属异常")
        }
        val previousCredentials = credentialsRepository.getCredentials(profileId)
        try {
            credentialsRepository.saveCredentials(profileId, credentials)
        } catch (failure: Throwable) {
            val rollbackFailure = restoreCredentials(profileId, previousCredentials)
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure)
                return@withOperationalProfileMutation CredentialBindingMutationResult.Inconsistent("凭据写入失败且无法恢复原凭据")
            }
            if (failure is CancellationException) throw failure
            return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("凭据写入失败")
        }
        try {
            val sourceBindingId = database.withTransaction {
                if (semester == null) return@withTransaction null
                val currentProfile = profileDao.getProfileById(profileId)
                    ?: error("课表在重绑期间被删除")
                val currentSemester = semesterDao.getSemesterById(semester.id)
                    ?: error("学期在重绑期间被删除")
                check(currentProfile.activeSemesterId == semester.id && currentSemester.profileId == profileId) {
                    "活动学期在重绑期间发生变化"
                }
                val existing = bindingDao.getBySemesterOnce(semester.id)
                check(existing == oldBinding) { "同步来源绑定在重绑期间发生变化" }
                // 显式保存可能是同 provider 换账号；始终轮换 UUID，立即作废旧账号在途任务。
                if (existing != null) bindingDao.deleteBySemester(semester.id)
                val now = System.currentTimeMillis()
                val replacement = SyncSourceBindingEntity(
                    sourceBindingId = UUID.randomUUID().toString(),
                    profileId = profileId,
                    semesterId = semester.id,
                    provider = credentials.provider.name,
                    createdAt = now,
                    updatedAt = now,
                )
                bindingDao.insert(replacement)
                replacement.sourceBindingId
            }
            CredentialBindingMutationResult.Success(sourceBindingId)
        } catch (failure: Throwable) {
            val rollbackFailure = restoreCredentials(profileId, previousCredentials)
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure)
                CredentialBindingMutationResult.Inconsistent("来源重绑失败且无法恢复原凭据")
            } else {
                if (failure is CancellationException) throw failure
                CredentialBindingMutationResult.Rejected("来源重绑失败，原凭据已恢复")
            }
        }
    }

    /** 清除凭据与 binding，先使文件失效，再在同一锁内使旧网络任务失效。 */
    suspend fun clearCredentialsAndUnbindIfActive(
        profileId: Long,
    ): CredentialBindingMutationResult = withOperationalProfileMutation {
        val activeProfileId = resolveAndRepairSelectionLocked(activeSelectionStore.rawActiveProfileId.first())
        if (activeProfileId != profileId || profileDao.getProfileById(profileId) == null) {
            return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("活动课表已变化")
        }
        val previousCredentials = credentialsRepository.getCredentials(profileId)
        try {
            credentialsRepository.clearCredentials(profileId)
        } catch (failure: Throwable) {
            val rollbackFailure = restoreCredentials(profileId, previousCredentials)
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure)
                return@withOperationalProfileMutation CredentialBindingMutationResult.Inconsistent("清除凭据失败且无法恢复")
            }
            if (failure is CancellationException) throw failure
            return@withOperationalProfileMutation CredentialBindingMutationResult.Rejected("清除凭据失败")
        }
        try {
            database.withTransaction { bindingDao.deleteByProfile(profileId) }
            CredentialBindingMutationResult.Success(null)
        } catch (failure: Throwable) {
            val rollbackFailure = restoreCredentials(profileId, previousCredentials)
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure)
                CredentialBindingMutationResult.Inconsistent("解绑失败且无法恢复原凭据")
            } else {
                if (failure is CancellationException) throw failure
                CredentialBindingMutationResult.Rejected("解绑失败，原凭据已恢复")
            }
        }
    }

    /** 网络同步提交门禁：只复核启动时捕获的固定目标与 binding，不依赖当前 UI 选择。 */
    suspend fun replaceCoursesForCapturedBinding(
        profileId: Long,
        semesterId: Long,
        provider: SyncProviderType,
        sourceBindingId: String,
        courses: List<Course>,
    ): Boolean = withOperationalProfileMutation {
        database.withTransaction {
            if (profileDao.getProfileById(profileId) == null) return@withTransaction false
            val semester = semesterDao.getSemesterById(semesterId) ?: return@withTransaction false
            if (semester.profileId != profileId) return@withTransaction false
            val binding = bindingDao.getBySemesterOnce(semesterId)
            if (binding == null || binding.sourceBindingId != sourceBindingId ||
                binding.profileId != profileId || binding.semesterId != semesterId ||
                binding.provider != provider.name
            ) return@withTransaction false
            courseDao.deleteCoursesBySemester(semesterId)
            if (courses.isNotEmpty()) {
                courseDao.insertCourses(courses.map { it.copy(id = 0L, semesterId = semesterId).toEntity() })
            }
            true
        }
    }

    private suspend fun resolveAndRepairSelectionLocked(rawProfileId: Long?): Long =
        DawnStartupTrace.section(DawnStartupTrace.RESOLVE_PROFILE_SELECTION) {
        var profiles = profileDao.getAllProfilesOnce()
        if (profiles.isEmpty()) {
            val created = TimetableProfileEntity(uuid = UUID.randomUUID().toString(), name = DEFAULT_PROFILE_NAME)
            val id = database.withTransaction { profileDao.insert(created) }
            profiles = listOf(created.copy(id = id))
        }
        val resolved = if (rawProfileId == null) {
            val legacyProfile = legacySemesterSelectionStore.selectedSemesterId.first()
                ?.let { semesterDao.getSemesterById(it) }?.profileId
                ?.takeIf { candidate -> profiles.any { it.id == candidate } }
            val selected = legacyProfile ?: profiles.first().id
            try {
                activeSelectionStore.initializeIfUnset(selected)
            } catch (failure: Exception) {
                throw IllegalStateException("无法初始化 active_profile_id", failure)
            }
            selected
        } else {
            rawProfileId.takeIf { candidate -> profiles.any { it.id == candidate } }
                ?: profiles.first().id.also { fallbackId ->
                    try {
                        activeSelectionStore.selectProfile(fallbackId)
                    } catch (failure: Exception) {
                        throw IllegalStateException("无法修复无效 active_profile_id", failure)
                    }
                }
        }
        database.withTransaction {
            profiles.forEach { profile ->
                val activeSemester = profile.activeSemesterId?.let { semesterDao.getSemesterById(it) }
                if (profile.activeSemesterId != null && activeSemester?.profileId != profile.id) {
                    profileDao.updateActiveSemesterId(profile.id, null)
                }
            }
        }
        return resolved
    }

    private suspend fun createEmptyLocked(name: String): Pair<TimetableProfileEntity, SemesterEntity?> {
        val sortOrder = (profileDao.getAllProfilesOnce().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val profile = TimetableProfileEntity(
            uuid = UUID.randomUUID().toString(), name = name, sortOrder = sortOrder,
        )
        val id = profileDao.insert(profile)
        return profile.copy(id = id) to null
    }

    private suspend fun createWithSemesterLocked(
        name: String,
        spec: NewSemesterSpec,
    ): Pair<TimetableProfileEntity, SemesterEntity?> {
        validateSemesterSpec(spec)
        val (profile, _) = createEmptyLocked(name)
        val semester = SemesterEntity(
            profileId = profile.id, name = spec.name.trim(), startDate = spec.startDate, weekCount = spec.weekCount,
        )
        val id = semesterDao.insertSemester(semester)
        profileDao.updateActiveSemesterId(profile.id, id)
        return profile.copy(activeSemesterId = id) to semester.copy(id = id)
    }

    /** 克隆先验证来源，再两阶段重映射主键、课程族与绑定 UUID。 */
    private suspend fun createCloneLocked(
        name: String,
        sourceProfileId: Long,
    ): Pair<TimetableProfileEntity, SemesterEntity?> {
        val source = profileDao.getProfileById(sourceProfileId) ?: reject("克隆来源不存在")
        val semesters = semesterDao.getSemestersByProfileOnce(sourceProfileId)
        val semesterIds = semesters.mapTo(linkedSetOf()) { it.id }
        if (source.activeSemesterId != null && source.activeSemesterId !in semesterIds) {
            reject("来源课表的当前学期无效")
        }
        val courses = courseDao.getCoursesByProfileOnce(sourceProfileId).sortedBy { it.id }
        if (courses.any { it.semesterId !in semesterIds }) reject("来源课程跨越课表边界")
        val courseIds = courses.mapTo(linkedSetOf()) { it.id }
        courses.forEach { course ->
            if (course.originId == 0L || course.originId in courseIds) return@forEach
            if (courseDao.getCourseById(course.originId) != null) reject("课程来源指向其他课表")
            reject("课程来源记录已悬空")
        }
        val bindings = bindingDao.getByProfileOnce(sourceProfileId)
        if (bindings.any { it.profileId != sourceProfileId || it.semesterId !in semesterIds }) {
            reject("同步来源绑定跨越课表边界")
        }

        val (target, _) = createEmptyLocked(name)
        val semesterMap = linkedMapOf<Long, Long>()
        semesters.forEach { old ->
            semesterMap[old.id] = semesterDao.insertSemester(old.copy(id = 0L, profileId = target.id))
        }
        val courseMap = linkedMapOf<Long, Long>()
        courses.forEach { old ->
            courseMap[old.id] = courseDao.insertCourse(
                old.copy(id = 0L, semesterId = semesterMap.getValue(old.semesterId), originId = 0L),
            )
        }
        courses.forEach { old ->
            val remappedOrigin = if (old.originId == 0L) 0L else courseMap.getValue(old.originId)
            courseDao.updateCourse(
                old.copy(
                    id = courseMap.getValue(old.id), semesterId = semesterMap.getValue(old.semesterId),
                    originId = remappedOrigin,
                ),
            )
        }
        bindings.forEach { old ->
            bindingDao.insert(
                SyncSourceBindingEntity(
                    sourceBindingId = UUID.randomUUID().toString(), profileId = target.id,
                    semesterId = semesterMap.getValue(old.semesterId), provider = old.provider,
                    createdAt = old.createdAt, updatedAt = old.updatedAt,
                ),
            )
        }
        val activeId = source.activeSemesterId?.let(semesterMap::getValue)
        profileDao.updateActiveSemesterId(target.id, activeId)
        return target.copy(activeSemesterId = activeId) to activeId?.let { semesterDao.getSemesterById(it) }
    }

    private fun validateSemesterSpec(spec: NewSemesterSpec) {
        if (spec.name.trim().isEmpty()) reject("学期名称不能为空")
        if (spec.weekCount <= 0) reject("学期周数必须为正数")
    }

    private suspend fun validSemester(profile: TimetableProfileEntity): SemesterEntity? =
        profile.activeSemesterId?.let { semesterDao.getSemesterById(it) }?.takeIf { it.profileId == profile.id }

    private fun success(
        profile: TimetableProfileEntity,
        semester: SemesterEntity? = null,
    ): ProfileMutationResult.Success = ProfileMutationResult.Success(
        ActiveTimetableContext(
            profile = profile.toDomain(),
            semester = semester?.takeIf { it.profileId == profile.id && it.id == profile.activeSemesterId }?.toDomain(),
        ),
    )

    private suspend fun compensateDeleteAggregate(profileId: Long) {
        database.withTransaction { deleteAggregateLocked(profileId) }
    }

    /** 创建跨存储失败时，先恢复选择，再在 Profile 仍存在时清凭据，最后删除 Room aggregate。 */
    private suspend fun compensateCreatedProfile(
        profileId: Long,
        selectionPreimage: Long?,
    ): Throwable? = withContext(NonCancellable) {
        var firstFailure: Throwable? = null
        fun record(failure: Throwable) {
            val current = firstFailure
            if (current == null) firstFailure = failure else current.addSuppressed(failure)
        }
        try {
            activeSelectionStore.restoreRawSelection(selectionPreimage)
        } catch (failure: Throwable) {
            record(failure)
        }
        try {
            credentialsRepository.clearCredentials(profileId)
        } catch (failure: Throwable) {
            record(failure)
        }
        try {
            compensateDeleteAggregate(profileId)
        } catch (failure: Throwable) {
            record(failure)
        }
        firstFailure
    }

    /** DataStore 写入可能先落盘再抛错，因此任何失败都必须恢复精确 raw pre-image。 */
    private suspend fun restoreSelectionOrFail(
        selectionPreimage: Long?,
        originalFailure: Throwable,
        inconsistentMessage: String,
    ) {
        try {
            withContext(NonCancellable) { activeSelectionStore.restoreRawSelection(selectionPreimage) }
        } catch (rollbackFailure: Throwable) {
            originalFailure.addSuppressed(rollbackFailure)
            throw ConsistencyFailure(inconsistentMessage, originalFailure)
        }
    }

    /** 恢复凭据文件的精确 pre-image；Profile 必须仍存在。 */
    private suspend fun restoreCredentials(
        profileId: Long,
        previousCredentials: SyncCredentials?,
    ): Throwable? = withContext(NonCancellable) {
        try {
            if (previousCredentials == null) {
                credentialsRepository.clearCredentials(profileId)
            } else {
                credentialsRepository.saveCredentials(profileId, previousCredentials)
            }
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    private suspend fun deleteAggregateLocked(profileId: Long) {
        bindingDao.deleteByProfile(profileId)
        courseDao.deleteCoursesByProfile(profileId)
        semesterDao.deleteByProfile(profileId)
        profileDao.deleteById(profileId)
    }

    private suspend fun mutate(block: suspend () -> ProfileMutationResult): ProfileMutationResult = withOperationalProfileMutation {
        try {
            block()
        } catch (failure: BusinessRejection) {
            ProfileMutationResult.Rejected(failure.message ?: "请求被拒绝")
        } catch (failure: ConsistencyFailure) {
            ProfileMutationResult.Inconsistent(failure.message ?: "跨存储状态需要人工检查")
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            ProfileMutationResult.Rejected("操作失败，数据未完整提交")
        }
    }

    private fun reject(reason: String): Nothing = throw BusinessRejection(reason)
    private class BusinessRejection(message: String) : IllegalArgumentException(message)
    private class ConsistencyFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)

    private companion object {
        const val DEFAULT_PROFILE_NAME = "默认课表"
    }
}
