package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.ProfileCreationRequest
import com.dawncourse.core.domain.model.NewSemesterSpec
import com.dawncourse.core.domain.model.ProfileDeletionImpact
import com.dawncourse.core.domain.model.ProfileMutationResult
import com.dawncourse.core.domain.model.TimetableProfile
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.TimetableProfileSummary
import kotlinx.coroutines.flow.Flow

/** 多课表聚合的唯一业务入口，负责选择、隔离与生命周期约束。 */
interface TimetableProfileRepository {
    /** 观察所有可用课表。 */
    fun observeProfiles(): Flow<List<TimetableProfile>>

    /** 观察当前运行时选择及其所属学期。 */
    fun observeActiveContext(): Flow<ActiveTimetableContext?>

    /**
     * 在线性化选择区间内读取当前活动课表及其学期。
     *
     * 单次业务读取不得依赖共享观察流的 replay，以免紧随切换后的读取得到上一帧状态。
     */
    suspend fun getActiveContext(): ActiveTimetableContext?

    /** 观察指定课表的学期，不暴露跨课表记录。 */
    fun observeSemesters(profileId: Long): Flow<List<Semester>>

    /** 观察设置页的完整课表摘要。 */
    fun observeProfileSummaries(): Flow<List<TimetableProfileSummary>>

    /** 切换当前课表；目标不存在时不改变原选择。 */
    suspend fun switch(profileId: Long): ProfileMutationResult

    /** 设置当前课表的当前学期；该学期必须属于该课表。 */
    suspend fun setActiveSemester(profileId: Long, semesterId: Long?): ProfileMutationResult

    /** 创建空课表、含首学期课表或克隆课表。 */
    suspend fun create(request: ProfileCreationRequest): ProfileMutationResult

    /** 在既有课表内原子创建并激活一个学期。 */
    suspend fun createSemester(profileId: Long, semester: NewSemesterSpec): ProfileMutationResult

    /** 重命名课表。 */
    suspend fun rename(profileId: Long, name: String): ProfileMutationResult

    /** 删除前计算课程、绑定与选择回退影响。 */
    suspend fun previewDeletion(profileId: Long): ProfileDeletionImpact?

    /** 删除课表；删除当前课表时必须原子回退至其他课表。 */
    suspend fun delete(profileId: Long): ProfileMutationResult
}
