package com.dawncourse.feature.import_module.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dedupeParsedCourses] 契约：执行契约判出 `duplicate_ratio_high` 时，客户端按业务键
 * 二次去重补救本次导入。teacher / location 不参与键（不同采集分支清洗力度不同），
 * 保留首次出现的那条。
 */
class DedupeParsedCoursesTest {

    private fun course(
        name: String,
        teacher: String = "张三",
        location: String = "A101",
        dayOfWeek: Int = 2,
        startSection: Int = 7,
        duration: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = 0,
    ) = ParsedCourse(name, teacher, location, dayOfWeek, startSection, duration, startWeek, endWeek, weekType)

    @Test
    fun `同一门课两份（teacher location 清洗力度不同）折叠为一条`() {
        val input = listOf(
            course("传感器与检测技术", teacher = "张磊", location = "A101"),
            course("传感器与检测技术", teacher = "张磊 教学班:X 学分:3.0", location = "本部 A101"),
        )

        val result = dedupeParsedCourses(input)

        assertEquals(1, result.size)
        assertEquals("张磊", result.single().teacher) // 保留首次出现
    }

    @Test
    fun `2x 整体重复输入折叠为原始门数`() {
        val distinct = listOf(
            course("液压与气压传动", startSection = 1),
            course("科幻与想象力", startSection = 5),
            course("先进制造技术", startSection = 9, dayOfWeek = 4),
        )
        val doubled = (distinct + distinct).shuffled()

        assertEquals(3, dedupeParsedCourses(doubled).size)
    }

    @Test
    fun `调课拆分记录（时段相同但周次不同）不被误合并`() {
        val input = listOf(
            course("传感器与检测技术", startWeek = 1, endWeek = 8),
            course("传感器与检测技术", startWeek = 9, endWeek = 16),
        )

        assertEquals(2, dedupeParsedCourses(input).size)
    }

    @Test
    fun `不同课程名不合并`() {
        val input = listOf(course("A"), course("B"))

        assertEquals(2, dedupeParsedCourses(input).size)
    }
}
