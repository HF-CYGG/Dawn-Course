package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** CourseEditor 保存前必须在 ViewModel 层重新核验目标学期。 */
class CourseSaveSemesterValidatorTest {

    @Test
    fun zeroSemesterIdIsRejected() {
        assertNotNull(CourseSaveSemesterValidator.validate(listOf(course(semesterId = 0L)), null))
    }

    @Test
    fun missingRoomSemesterIsRejected() {
        assertNotNull(CourseSaveSemesterValidator.validate(listOf(course(semesterId = 4L)), null))
    }

    @Test
    fun mixedSemesterIdsAreRejected() {
        val semester = Semester(id = 4L, profileId = 1L, name = "当前学期", startDate = 1L, weekCount = 20)
        assertNotNull(
            CourseSaveSemesterValidator.validate(
                listOf(course(semesterId = 4L), course(semesterId = 5L)),
                semester
            )
        )
    }

    @Test
    fun existingPositiveSemesterIsAccepted() {
        val semester = Semester(id = 4L, profileId = 1L, name = "当前学期", startDate = 1L, weekCount = 20)
        assertNull(CourseSaveSemesterValidator.validate(listOf(course(semesterId = 4L)), semester))
    }

    private fun course(semesterId: Long) = Course(
        semesterId = semesterId,
        name = "课程",
        dayOfWeek = 1,
        startSection = 1,
        duration = 2,
        startWeek = 1,
        endWeek = 16
    )
}
