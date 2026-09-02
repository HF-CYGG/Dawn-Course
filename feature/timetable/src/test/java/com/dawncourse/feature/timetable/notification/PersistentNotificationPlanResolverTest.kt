package com.dawncourse.feature.timetable.notification

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [PersistentNotificationPlanResolver] 的纯 JVM 行为测试。
 */
class PersistentNotificationPlanResolverTest {
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")
    private val sectionTimes: List<SectionTime> = listOf(
        SectionTime("08:00", "08:45"),
        SectionTime("08:55", "09:40"),
        SectionTime("10:00", "10:45"),
        SectionTime("10:55", "11:40"),
        SectionTime("14:00", "14:45")
    )

    @Test
    fun `精确到开始时刻即进入上课中`() {
        val course = course(id = 1, startSection = 1, duration = 2)

        val plan = resolve("2026-08-24T08:00:00+08:00", courses = listOf(course))

        assertEquals(PersistentCourseStatus.IN_CLASS, plan.status)
        assertEquals(listOf(1L), plan.currentCourses.map { it.course.id })
        assertEquals(instant("2026-08-24T09:40:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `精确到结束时刻不再属于当前课程`() {
        val course = course(id = 1, startSection = 1, duration = 2)

        val plan = resolve("2026-08-24T09:40:00+08:00", courses = listOf(course))

        assertEquals(PersistentCourseStatus.FINISHED, plan.status)
        assertTrue(plan.currentCourses.isEmpty())
        assertEquals(instant("2026-08-25T00:00:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `课间返回下一组最早开始的课程`() {
        val first = course(id = 1, startSection = 1, duration = 1)
        val nextA = course(id = 3, startSection = 3, duration = 1)
        val nextB = course(id = 2, startSection = 3, duration = 1)

        val plan = resolve(
            "2026-08-24T09:00:00+08:00",
            courses = listOf(nextA, first, nextB)
        )

        assertEquals(PersistentCourseStatus.UPCOMING, plan.status)
        assertEquals(listOf(2L, 3L), plan.nextCourses.map { it.course.id })
        assertEquals(instant("2026-08-24T10:00:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `今天无课时返回无课并在下一本地午夜刷新`() {
        val tuesdayCourse = course(id = 1, dayOfWeek = 2)

        val plan = resolve("2026-08-24T12:00:00+08:00", courses = listOf(tuesdayCourse))

        assertEquals(PersistentCourseStatus.NO_COURSES, plan.status)
        assertTrue(plan.currentCourses.isEmpty())
        assertTrue(plan.nextCourses.isEmpty())
        assertEquals(instant("2026-08-25T00:00:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `无效学期周次不会展开课程`() {
        val plan = resolve(
            now = "2026-08-24T08:10:00+08:00",
            currentWeek = 19,
            weekCount = 18,
            courses = listOf(course(id = 1, startWeek = 1, endWeek = 20))
        )

        assertEquals(PersistentCourseStatus.NO_COURSES, plan.status)
        assertTrue(plan.currentCourses.isEmpty())
        assertEquals(instant("2026-08-25T00:00:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `单双周过滤与当前周奇偶一致`() {
        val odd = course(id = 1, weekType = Course.WEEK_TYPE_ODD)
        val even = course(id = 2, weekType = Course.WEEK_TYPE_EVEN)

        val oddWeekPlan = resolve(
            "2026-08-24T08:10:00+08:00",
            currentWeek = 3,
            courses = listOf(even, odd)
        )
        val evenWeekPlan = resolve(
            "2026-08-24T08:10:00+08:00",
            currentWeek = 4,
            courses = listOf(even, odd)
        )

        assertEquals(listOf(1L), oddWeekPlan.currentCourses.map { it.course.id })
        assertEquals(listOf(2L), evenWeekPlan.currentCourses.map { it.course.id })
    }

    @Test
    fun `非法节次或时间会被跳过且不影响其他课程`() {
        val invalidStartSection = course(id = 1, startSection = 0)
        val invalidDuration = course(id = 2, duration = 0)
        val outsideTable = course(id = 3, startSection = 5, duration = 2)
        val valid = course(id = 4, startSection = 1)
        val invalidTime = course(id = 5, startSection = 3)
        val invalidTimes = sectionTimes.toMutableList().apply {
            this[2] = SectionTime("25:00", "10:45")
        }

        val plan = PersistentNotificationPlanResolver.resolve(
            now = instant("2026-08-24T08:10:00+08:00"),
            zoneId = zoneId,
            currentWeek = 1,
            weekCount = 18,
            courses = listOf(
                invalidStartSection,
                invalidDuration,
                outsideTable,
                invalidTime,
                valid
            ),
            sectionTimes = invalidTimes
        )

        assertEquals(listOf(4L), plan.currentCourses.map { it.course.id })
    }

    @Test
    fun `重叠课程保留全集且下一刷新取所有结束边界最早值`() {
        val mainCourse = course(id = 1, startSection = 1, duration = 4)
        val shorterOverlap = course(id = 2, startSection = 2, duration = 2)

        val plan = resolve(
            "2026-08-24T09:00:00+08:00",
            courses = listOf(shorterOverlap, mainCourse)
        )

        assertEquals(listOf(1L, 2L), plan.currentCourses.map { it.course.id })
        assertEquals(instant("2026-08-24T10:45:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `当前课程按开始时间节次和课程ID稳定排序`() {
        val later = course(id = 1, startSection = 2, duration = 3)
        val sameStartHigherId = course(id = 9, startSection = 1, duration = 4)
        val sameStartLowerId = course(id = 3, startSection = 1, duration = 4)

        val plan = resolve(
            "2026-08-24T09:00:00+08:00",
            courses = listOf(later, sameStartHigherId, sameStartLowerId)
        )

        assertEquals(listOf(3L, 9L, 1L), plan.currentCourses.map { it.course.id })
    }

    @Test
    fun `课程未来边界晚于午夜时下一刷新仍取本地午夜`() {
        val lateTimes = listOf(SectionTime("23:30", "23:59"))

        val plan = PersistentNotificationPlanResolver.resolve(
            now = instant("2026-08-24T23:59:30+08:00"),
            zoneId = zoneId,
            currentWeek = 1,
            weekCount = 18,
            courses = listOf(course(id = 1)),
            sectionTimes = lateTimes
        )

        assertEquals(instant("2026-08-25T00:00:00+08:00"), plan.nextRefreshAt)
    }

    @Test
    fun `发布时已跨过边界会只基于新时刻重算计划`() {
        val initialPlan = resolve(
            now = "2026-08-24T08:44:59+08:00",
            courses = listOf(course(id = 1))
        )

        val publicationPlan = PersistentNotificationPlanResolver.recalculateForPublication(
            initialPlan = initialPlan,
            publicationNow = instant("2026-08-24T08:45:00+08:00"),
            zoneId = zoneId,
            currentWeek = 1,
            weekCount = 18,
            courses = listOf(course(id = 1)),
            sectionTimes = sectionTimes
        )

        assertEquals(PersistentCourseStatus.FINISHED, publicationPlan.status)
        assertTrue(publicationPlan.currentCourses.isEmpty())
        assertEquals(instant("2026-08-25T00:00:00+08:00"), publicationPlan.nextRefreshAt)
    }

    @Test
    fun `发布时尚未跨过边界会复用原计划`() {
        val initialPlan = resolve(
            now = "2026-08-24T08:10:00+08:00",
            courses = listOf(course(id = 1))
        )

        val publicationPlan = PersistentNotificationPlanResolver.recalculateForPublication(
            initialPlan = initialPlan,
            publicationNow = instant("2026-08-24T08:20:00+08:00"),
            zoneId = zoneId,
            currentWeek = 1,
            weekCount = 18,
            courses = listOf(course(id = 1)),
            sectionTimes = sectionTimes
        )

        assertTrue(initialPlan === publicationPlan)
    }

    private fun resolve(
        now: String,
        currentWeek: Int = 1,
        weekCount: Int = 18,
        courses: List<Course>
    ): PersistentNotificationPlan = PersistentNotificationPlanResolver.resolve(
        now = instant(now),
        zoneId = zoneId,
        currentWeek = currentWeek,
        weekCount = weekCount,
        courses = courses,
        sectionTimes = sectionTimes
    )

    private fun instant(value: String): Instant = ZonedDateTime.parse(value).toInstant()

    private fun course(
        id: Long,
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        duration: Int = 1,
        startWeek: Int = 1,
        endWeek: Int = 18,
        weekType: Int = Course.WEEK_TYPE_ALL
    ): Course = Course(
        id = id,
        semesterId = 1,
        name = "课程$id",
        location = "教室$id",
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = duration,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType
    )
}
