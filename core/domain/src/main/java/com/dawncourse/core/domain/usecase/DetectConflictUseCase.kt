package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.Course
import javax.inject.Inject

/**
 * 课程冲突检测用例
 */
class DetectConflictUseCase @Inject constructor() {

    /**
     * 检测目标课程是否与现有课程列表冲突
     *
     * @param targetCourse 待检测的课程
     * @param existingCourses 现有的课程列表
     * @return 冲突的课程列表，无冲突则为空列表
     */
    operator fun invoke(targetCourse: Course, existingCourses: List<Course>): List<Course> {
        return existingCourses.filter { course ->
            // 1. 忽略自身（如果是更新操作，ID 相同则忽略）
            if (course.id != 0L && course.id == targetCourse.id) return@filter false
            
            // 2. 检查星期是否相同
            if (course.dayOfWeek != targetCourse.dayOfWeek) return@filter false
            
            // 3. 检查周次是否有交集
            val weekOverlap = isWeekOverlap(course, targetCourse)
            if (!weekOverlap) return@filter false
            
            // 4. 检查节次是否有重叠
            val sectionOverlap = isSectionOverlap(course, targetCourse)
            sectionOverlap
        }
    }

    private fun isWeekOverlap(c1: Course, c2: Course): Boolean {
        // 简单周次范围检查
        val startMax = maxOf(c1.startWeek, c2.startWeek)
        val endMin = minOf(c1.endWeek, c2.endWeek)
        
        if (startMax > endMin) return false
        
        // 详细单双周检查
        // 如果两者都是全周，或者一个是全周，肯定有交集（在范围重叠的前提下）
        if (c1.weekType == Course.WEEK_TYPE_ALL || c2.weekType == Course.WEEK_TYPE_ALL) return true
        
        // 如果都是单周或都是双周，有交集
        if (c1.weekType == c2.weekType) return true
        
        // 一个单周一个双周，无交集
        return false
    }

    private fun isSectionOverlap(c1: Course, c2: Course): Boolean {
        val s1Start = c1.startSection
        val s1End = c1.startSection + c1.duration - 1
        val s2Start = c2.startSection
        val s2End = c2.startSection + c2.duration - 1
        
        return maxOf(s1Start, s2Start) <= minOf(s1End, s2End)
    }
}
