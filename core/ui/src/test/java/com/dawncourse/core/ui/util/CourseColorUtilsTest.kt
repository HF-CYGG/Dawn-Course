package com.dawncourse.core.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 课程颜色散列边界的 JVM 回归测试。 */
class CourseColorUtilsTest {
    @Test
    fun `Int 最小值散列仍映射到有效预设颜色`() {
        val name = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, name.hashCode())

        val expectedIndex = Math.floorMod(name.hashCode(), CourseColorUtils.getPresetColors().size)
        assertEquals(
            CourseColorUtils.getPresetColors()[expectedIndex],
            CourseColorUtils.generateColor(name, teacher = null),
        )
    }
}
