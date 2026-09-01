package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入写入的回归门禁：覆盖只能作用于指定学期，绝不能沿用全库清空。 */
class ImportCommitRepositoryContractTest {

    @Test
    fun importCommitUsesSingleRoomTransactionAndNeverClearsAllCoursesOrSemesters() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/ImportCommitRepositoryImpl.kt",
        ).readText()

        assertTrue(source.contains("database.withTransaction { commitInTransaction(request) }"))
        assertTrue(source.contains("courseDao.deleteCoursesBySemester(oldSemester.id)"))
        assertTrue(source.contains("semesterId = semesterId"))
        assertTrue(source.contains("originId = 0L"))
        assertFalse(source.contains("deleteAllCourses()"))
        assertFalse(source.contains("deleteAllSemesters()"))
    }

    @Test
    fun selectionFailureCompensatesInNonCancellableContextAndExposesInconsistentState() {
        val source = File(
            "src/main/java/com/dawncourse/core/data/repository/ImportCommitRepositoryImpl.kt",
        ).readText()

        assertTrue(source.contains("withContext(NonCancellable)"))
        assertTrue(source.contains("ImportCommitResult.Inconsistent"))
        assertTrue(source.contains("if (selectionFailure is CancellationException) throw selectionFailure"))
    }
}
