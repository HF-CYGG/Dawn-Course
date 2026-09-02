package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLayoutEngineTest {

    @Test
    fun `legacy 富化教师和本部地点与简洁记录合并并保留首项`() {
        val concise = course(id = 99, teacher = "方金", location = "本部 J3-406")
        val legacyEnriched = concise.copy(
            id = 1,
            teacher = "方金 教学班:2026班 教学班组成:1班 考核方式:考试 课程学时组成:32",
            location = "J3-406"
        )

        val items = layoutOf(concise, legacyEnriched)

        assertEquals(1, items.size)
        assertEquals(concise.id, items.single().course.id)
        assertEquals(concise.teacher, items.single().course.teacher)
        assertEquals(concise.location, items.single().course.location)
        assertEquals(1, items.single().laneCount)
    }

    @Test
    fun `任一 legacy 教师确认标记都能识别富化记录`() {
        val concise = course(id = 99, teacher = "方金")
        listOf(
            "方金 教学班:2026班 教学班组成:1班",
            "方金 教学班:2026班 考核方式:考试",
            "方金 教学班:2026班 课程学时组成:32"
        ).forEach { enrichedTeacher ->
            val items = layoutOf(concise, concise.copy(id = 1, teacher = enrichedTeacher))

            assertEquals(enrichedTeacher, 1, items.size)
            assertEquals(enrichedTeacher, concise.id, items.single().course.id)
        }
    }

    @Test
    fun `不满足 legacy 确认形态的教师和地点差异不得合并`() {
        val concise = course(id = 99, teacher = "方金", location = "J3-406")
        listOf(
            concise.copy(id = 1, teacher = "方金 教学班:2026班"),
            concise.copy(id = 1, teacher = " 教学班:2026班 教学班组成:1班"),
            concise.copy(id = 1, location = "南校区 J3-406"),
            concise.copy(id = 1, location = "本部J3-406")
        ).forEach { variant ->
            val items = layoutOf(concise, variant)

            assertEquals(2, items.size)
            assertEquals(setOf(concise.id, variant.id), items.map { it.course.id }.toSet())
            assertContinuousUniqueLanes("非 legacy 确认形态", items)
        }
    }

    @Test
    fun `仅存储身份不同的当前周重复课程只渲染输入中的第一项`() {
        val firstStoredRecord = course(id = 99, color = "#FF0000", originId = 11)
        val secondStoredRecord = firstStoredRecord.copy(id = 1, color = "#00FF00", originId = 22)

        val items = layoutOf(firstStoredRecord, secondStoredRecord)

        assertEquals(1, items.size)
        assertEquals(1, items.single().laneCount)
        assertEquals(firstStoredRecord.id, items.single().course.id)
    }

    @Test
    fun `当前周语义字段任一不同都会保留课程`() {
        val base = course(id = 99)
        listOf(
            SemanticDifferenceCase("semesterId", base.copy(id = 1, semesterId = 2), true),
            SemanticDifferenceCase("name", base.copy(id = 1, name = "算法设计"), true),
            SemanticDifferenceCase("teacher", base.copy(id = 1, teacher = "李老师"), true),
            SemanticDifferenceCase("location", base.copy(id = 1, location = "B202"), true),
            SemanticDifferenceCase("dayOfWeek", base.copy(id = 1, dayOfWeek = 2), false),
            SemanticDifferenceCase("startSection", base.copy(id = 1, startSection = 2), true),
            SemanticDifferenceCase("duration", base.copy(id = 1, duration = 3), true),
            SemanticDifferenceCase("startWeek", base.copy(id = 1, startWeek = 2), true),
            SemanticDifferenceCase("endWeek", base.copy(id = 1, endWeek = 3), true),
            SemanticDifferenceCase("weekType", base.copy(id = 1, weekType = Course.WEEK_TYPE_ODD), true),
            SemanticDifferenceCase("isModified", base.copy(id = 1, isModified = true), true),
            SemanticDifferenceCase("note", base.copy(id = 1, note = "调课备注"), true)
        ).forEach { difference ->
            val items = layoutOf(base, difference.course)

            assertEquals(difference.field, 2, items.size)
            assertEquals(difference.field, setOf(base.id, difference.course.id), items.map { it.course.id }.toSet())
            if (difference.hasOverlap) {
                assertContinuousUniqueLanes(difference.field, items)
            }
        }
    }

    @Test
    fun `字符串前后空格和大小写差异按精确值保留`() {
        val base = course(id = 99, name = "Data Structures", teacher = "ALICE", location = "ROOM A")
        listOf(
            base.copy(id = 1, name = " Data Structures"),
            base.copy(id = 1, name = "Data Structures "),
            base.copy(id = 1, teacher = "alice"),
            base.copy(id = 1, location = "room a")
        ).forEach { variant ->
            val items = layoutOf(base, variant)

            assertEquals(2, items.size)
            assertEquals(setOf(base.id, variant.id), items.map { it.course.id }.toSet())
            assertContinuousUniqueLanes("字符串精确匹配", items)
        }
    }

    @Test
    fun `整学期课程和仅单周的不同课程在适用周同时显示`() {
        val fullSemester = course(id = 99, startWeek = 1, endWeek = 16)
        val oneWeek = fullSemester.copy(id = 1, startWeek = 3, endWeek = 3)

        val applicableWeekItems = layoutOf(fullSemester, oneWeek, currentWeek = 3)
        val otherWeekItems = layoutOf(fullSemester, oneWeek, currentWeek = 2)

        assertEquals(2, applicableWeekItems.size)
        assertContinuousUniqueLanes("整学期与单周课程", applicableWeekItems)
        assertEquals(1, otherWeekItems.size)
        assertEquals(fullSemester.id, otherWeekItems.single().course.id)
    }

    @Test
    fun `同名但周次语义不同且当前周重叠的课程不得去重`() {
        val fullSemester = course(id = 99, name = "英语", startWeek = 1, endWeek = 16)
        val laterStart = fullSemester.copy(id = 2, startWeek = 3, endWeek = 16)
        val oddWeeks = fullSemester.copy(id = 1, weekType = Course.WEEK_TYPE_ODD)

        val items = layoutOf(fullSemester, laterStart, oddWeeks, currentWeek = 3)

        assertEquals(3, items.size)
        assertContinuousUniqueLanes("周次语义不同课程", items)
    }

    @Test
    fun `起始节次不同但区间重叠的课程继续分栏`() {
        val first = course(id = 99, startSection = 1, duration = 3)
        val second = course(id = 1, startSection = 2, duration = 2)

        val items = layoutOf(first, second)

        assertEquals(2, items.size)
        assertContinuousUniqueLanes("不同起始节次重叠课程", items)
    }

    @Test
    fun `隐藏非本周和周末过滤保持原有行为`() {
        val nonCurrentWeekday = course(id = 99, dayOfWeek = 1, startWeek = 1, endWeek = 1)
        val currentWeekend = course(id = 1, dayOfWeek = 7)

        val hiddenAndNoWeekend = layoutOf(
            nonCurrentWeekday,
            currentWeekend,
            hideNonThisWeek = true,
            showWeekend = false
        )
        val representativeOnly = layoutOf(
            nonCurrentWeekday,
            currentWeekend,
            hideNonThisWeek = false,
            showWeekend = false
        )
        val withWeekend = layoutOf(
            nonCurrentWeekday,
            currentWeekend,
            hideNonThisWeek = false,
            showWeekend = true
        )

        assertTrue(hiddenAndNoWeekend.isEmpty())
        assertEquals(1, representativeOnly.size)
        assertFalse(representativeOnly.single().isCurrentWeek)
        assertEquals(2, withWeekend.size)
        assertTrue(withWeekend.any { it.course.id == currentWeekend.id && it.isCurrentWeek })
    }

    @Test
    fun `截图形态数据会移除六个存储身份重复项`() {
        val uniqueCourses = (1..11).map { index ->
            course(
                id = 100L + index,
                name = "课程$index",
                dayOfWeek = (index - 1) % 5 + 1,
                startSection = (index - 1) / 5 + 1,
                duration = 1
            )
        }
        val storageDuplicates = uniqueCourses.take(6).mapIndexed { index, course ->
            course.copy(
                id = (index + 1).toLong(),
                color = "#${index}0${index}0${index}0",
                originId = 200L + index
            )
        }

        val items = layoutOf(*(uniqueCourses + storageDuplicates).toTypedArray())

        assertEquals(11, items.size)
        assertEquals(uniqueCourses.map { it.id }.toSet(), items.map { it.course.id }.toSet())
        assertTrue(items.all { it.laneCount == 1 })
    }

    private fun assertContinuousUniqueLanes(description: String, items: List<TimetableLayoutItem>) {
        val laneIndexes = items.map { it.laneIndex }
        assertEquals("$description laneCount", items.size, items.first().laneCount)
        assertTrue("$description laneCount 一致", items.all { it.laneCount == items.size })
        assertEquals("$description laneIndex 唯一", items.size, laneIndexes.toSet().size)
        assertEquals("$description laneIndex 连续", (0 until items.size).toSet(), laneIndexes.toSet())
    }

    private fun layoutOf(
        vararg courses: Course,
        currentWeek: Int = 3,
        hideNonThisWeek: Boolean = false,
        showWeekend: Boolean = true
    ): List<TimetableLayoutItem> = TimetableLayoutEngine.calculateLayoutItems(
        courses = courses.toList(),
        currentWeek = currentWeek,
        maxNodes = 12,
        hideNonThisWeek = hideNonThisWeek,
        showWeekend = showWeekend
    )

    private fun course(
        id: Long = 1,
        semesterId: Long = 1,
        name: String = "数据结构",
        teacher: String = "张老师",
        location: String = "A101",
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        duration: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = Course.WEEK_TYPE_ALL,
        color: String = "#123456",
        isModified: Boolean = false,
        note: String = "",
        originId: Long = 0
    ) = Course(
        id = id,
        semesterId = semesterId,
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = duration,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        color = color,
        isModified = isModified,
        note = note,
        originId = originId
    )

    private data class SemanticDifferenceCase(
        val field: String,
        val course: Course,
        val hasOverlap: Boolean
    )
}
