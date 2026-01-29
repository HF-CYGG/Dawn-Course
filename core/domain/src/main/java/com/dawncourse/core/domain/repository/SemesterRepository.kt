package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.Semester
import kotlinx.coroutines.flow.Flow

/**
 * 学期数据仓库接口
 *
 * 定义了对学期数据的增删改查操作。
 */
interface SemesterRepository {
    /**
     * 获取所有学期列表
     */
    fun getAllSemesters(): Flow<List<Semester>>

    /**
     * 获取当前激活的学期
     *
     * @return 返回当前学期的 Flow，如果没有设置当前学期则可能发射 null
     */
    fun getCurrentSemester(): Flow<Semester?>

    /**
     * 根据 ID 获取学期
     *
     * @param id 学期 ID
     * @return 返回对应的学期对象，若不存在则返回 null
     */
    suspend fun getSemesterById(id: Long): Semester?

    /**
     * 插入新学期
     *
     * @param semester 要插入的学期对象
     * @return 新插入学期的 ID
     */
    suspend fun insertSemester(semester: Semester): Long

    /**
     * 更新学期信息
     *
     * @param semester 包含更新信息的学期对象
     */
    suspend fun updateSemester(semester: Semester)

    /**
     * 删除学期
     *
     * @param semester 要删除的学期对象
     */
    suspend fun deleteSemester(semester: Semester)

    /**
     * 设置当前学期
     *
     * 将指定 ID 的学期标记为当前学期，并自动取消其他学期的当前状态。
     *
     * @param id 要设置为当前的学期 ID
     */
    suspend fun setCurrentSemester(id: Long)
}
