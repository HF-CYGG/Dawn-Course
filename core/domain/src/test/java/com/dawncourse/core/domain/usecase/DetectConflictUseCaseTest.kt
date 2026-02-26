package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.Course
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DetectConflictUseCase] 的单元测试。
 *
 * 覆盖重点：
 * 1) 单双周规则：周次范围有交集时，若一方单周一方双周，则不应判定冲突。
 * 2) 节次区间：使用闭区间 [startSection, startSection + duration - 1] 判断是否重叠，
 *    当两段在边界节次相接（例如 1-2 与 2-3）时，应视为重叠（存在冲突）。
 */
class DetectConflictUseCaseTest {

    private val useCase = DetectConflictUseCase()

    @Test
    fun `节次区间在边界处重叠时判定冲突`() {
        // 现有课程：第 1-2 节
        val existing = course(
            id = 1L,
            dayOfWeek = 1,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 16,
            weekType = Course.WEEK_TYPE_ALL
        )

        // 目标课程：第 2-3 节，与现有课程在“第 2 节”重叠
        val target = course(
            id = 2L,
            dayOfWeek = 1,
            startSection = 2,
            duration = 2,
            startWeek = 1,
            endWeek = 16,
            weekType = Course.WEEK_TYPE_ALL
        )

        val conflicts = useCase(targetCourse = target, existingCourses = listOf(existing))
        assertEquals(listOf(existing), conflicts)
    }

    @Test
    fun `节次区间完全不相交时不冲突`() {
        // 现有课程：第 1-2 节
        val existing = course(
            id = 1L,
            dayOfWeek = 1,
            startSection = 1,
            duration = 2
        )

        // 目标课程：第 3-4 节，与现有课程不相交
        val target = course(
            id = 2L,
            dayOfWeek = 1,
            startSection = 3,
            duration = 2
        )

        val conflicts = useCase(targetCourse = target, existingCourses = listOf(existing))
        assertEquals(emptyList<Course>(), conflicts)
    }

    @Test
    fun `单双周不同且周次范围重叠时不冲突`() {
        // 现有课程：单周上课
        val existingOdd = course(
            id = 1L,
            dayOfWeek = 3,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 16,
            weekType = Course.WEEK_TYPE_ODD
        )

        // 目标课程：双周上课（周次范围完全重叠、节次也重叠，但单双周互斥）
        val targetEven = course(
            id = 2L,
            dayOfWeek = 3,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 16,
            weekType = Course.WEEK_TYPE_EVEN
        )

        val conflicts = useCase(targetCourse = targetEven, existingCourses = listOf(existingOdd))
        assertEquals(emptyList<Course>(), conflicts)
    }

    @Test
    fun `周次范围无交集时不冲突`() {
        // 现有课程：第 1-8 周
        val existing = course(
            id = 1L,
            dayOfWeek = 5,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 8,
            weekType = Course.WEEK_TYPE_ALL
        )

        // 目标课程：第 9-16 周（周次范围不相交）
        val target = course(
            id = 2L,
            dayOfWeek = 5,
            startSection = 1,
            duration = 2,
            startWeek = 9,
            endWeek = 16,
            weekType = Course.WEEK_TYPE_ALL
        )

        val conflicts = useCase(targetCourse = target, existingCourses = listOf(existing))
        assertEquals(emptyList<Course>(), conflicts)
    }

    /**
     * 构造测试用课程对象。
     *
     * 说明：
     * - 仅填充冲突检测所需的字段（星期、节次、周次、单双周等）。
     * - 其他字段使用默认值，避免测试关注点扩散。
     */
    private fun course(
        id: Long,
        dayOfWeek: Int,
        startSection: Int,
        duration: Int,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = Course.WEEK_TYPE_ALL
    ): Course {
        return Course(
            id = id,
            name = "测试课程$id",
            dayOfWeek = dayOfWeek,
            startSection = startSection,
            duration = duration,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = weekType
        )
    }
}

