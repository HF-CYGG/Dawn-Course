package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程最终写入必须在同一 Room transaction 中验证学期并完成替换。 */
class CourseRepositoryAtomicSaveContractTest {
    @Test
    fun atomicSaveChecksSemesterAndWritesInsideRoomTransaction() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/CourseRepositoryImpl.kt"
        ).readText()
        val method = source.substringAfter("override suspend fun saveCoursesAtomically")

        assertTrue(method.contains("database.withTransaction"))
        assertTrue(method.contains("semesterDao().getSemesterById"))
        assertTrue(method.contains("courseDao.deleteCourseById"))
        assertTrue(method.contains("courseDao.insertCourses"))
    }
}
