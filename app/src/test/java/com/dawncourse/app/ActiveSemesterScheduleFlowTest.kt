package com.dawncourse.app

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.TimetableProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 当前学期与课程原子配对 Flow 的回归测试。
 */
class ActiveSemesterScheduleFlowTest {
    @Test
    fun `切换学期时不会发射新学期配旧课程的瞬时组合`() = runBlocking {
        val firstSemester = semester(id = 1)
        val secondSemester = semester(id = 2)
        val activeContext = MutableStateFlow(context(firstSemester))
        val firstCourses = MutableStateFlow(listOf(course(id = 11, semesterId = 1)))
        val secondCourses = MutableStateFlow(listOf(course(id = 22, semesterId = 2)))
        val emissions = Channel<ActiveSemesterSchedule>(capacity = Channel.UNLIMITED)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            pairActiveSemesterSchedule(activeContext) { semesterId ->
                if (semesterId == 1L) firstCourses else secondCourses
            }.collect { value -> emissions.send(value) }
        }

        val firstEmission = withTimeout(1_000) { emissions.receive() }
        activeContext.value = context(secondSemester)
        val secondEmission = withTimeout(1_000) {
            var value = emissions.receive()
            while (value.semester?.id != secondSemester.id) {
                value = emissions.receive()
            }
            value
        }
        collection.cancel()

        assertEquals(1L, firstEmission.semester?.id)
        assertEquals(2L, secondEmission.semester?.id)
        assertFalse(
            secondEmission.courses.any { course -> course.semesterId == 1L }
        )
    }

    private fun semester(id: Long): Semester = Semester(
        id = id,
        profileId = 1L,
        name = "学期$id",
        startDate = 1_700_000_000_000,
        weekCount = 18,
        isCurrent = true
    )

    private fun context(semester: Semester): ActiveTimetableContext = ActiveTimetableContext(
        profile = TimetableProfile(
            id = semester.profileId,
            uuid = "00000000-0000-0000-0000-${semester.profileId.toString().padStart(12, '0')}",
            name = "课表${semester.profileId}",
            activeSemesterId = semester.id,
        ),
        semester = semester,
    )

    private fun course(id: Long, semesterId: Long): Course = Course(
        id = id,
        semesterId = semesterId,
        name = "课程$id",
        dayOfWeek = 1,
        startSection = 1,
        duration = 1,
        startWeek = 1,
        endWeek = 18
    )
}
