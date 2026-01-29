package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.Semester
import kotlinx.coroutines.flow.Flow

interface SemesterRepository {
    fun getAllSemesters(): Flow<List<Semester>>
    fun getCurrentSemester(): Flow<Semester?>
    suspend fun getSemesterById(id: Long): Semester?
    suspend fun insertSemester(semester: Semester): Long
    suspend fun updateSemester(semester: Semester)
    suspend fun deleteSemester(semester: Semester)
    suspend fun setCurrentSemester(id: Long)
}
