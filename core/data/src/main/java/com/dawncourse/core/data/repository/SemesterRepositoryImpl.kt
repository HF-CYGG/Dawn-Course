package com.dawncourse.core.data.repository

import androidx.room.withTransaction
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.local.entity.toDomain
import com.dawncourse.core.data.local.entity.toEntity
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.repository.SemesterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * 学期数据仓库的实现类
 *
 * 基于 Room 数据库实现学期数据的持久化存储。
 * 负责协调 [SemesterDao] 进行数据库操作，并处理事务逻辑（如设置当前学期时清除其他标记）。
 */
class SemesterRepositoryImpl @Inject constructor(
    private val semesterDao: SemesterDao,
    private val database: AppDatabase
) : SemesterRepository {

    override fun getAllSemesters(): Flow<List<Semester>> {
        return semesterDao.getAllSemesters().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getCurrentSemester(): Flow<Semester?> {
        return semesterDao.getCurrentSemester().map { it?.toDomain() }
    }

    override suspend fun getSemesterById(id: Long): Semester? {
        return semesterDao.getSemesterById(id)?.toDomain()
    }

    override suspend fun insertSemester(semester: Semester): Long {
        // 如果插入的学期被标记为当前学期，则需要在一个事务中清除其他学期的标记
        if (semester.isCurrent) {
            return database.withTransaction {
                val id = semesterDao.insertSemester(semester.toEntity())
                semesterDao.clearOtherCurrentFlags(id)
                id
            }
        }
        return semesterDao.insertSemester(semester.toEntity())
    }

    override suspend fun updateSemester(semester: Semester) {
        if (semester.isCurrent) {
            database.withTransaction {
                semesterDao.updateSemester(semester.toEntity())
                semesterDao.clearOtherCurrentFlags(semester.id)
            }
        } else {
            semesterDao.updateSemester(semester.toEntity())
        }
    }

    override suspend fun deleteSemester(semester: Semester) {
        semesterDao.deleteSemester(semester.toEntity())
    }
    
    override suspend fun setCurrentSemester(id: Long) {
        database.withTransaction {
            val semester = semesterDao.getSemesterById(id)
            if (semester != null) {
                semesterDao.updateSemester(semester.copy(isCurrent = true))
                semesterDao.clearOtherCurrentFlags(id)
            }
        }
    }
}
