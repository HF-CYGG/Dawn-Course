package com.dawncourse.feature.timetable

import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.Semester

/** 课程编辑器在最终写入前执行的学期归属校验。 */
internal object CourseSaveSemesterValidator {
    fun validate(courses: List<Course>, semester: Semester?): String? {
        if (courses.isEmpty()) return "未选择任何周次，无法保存课程"
        val semesterIds = courses.map { it.semesterId }.toSet()
        if (semesterIds.size != 1 || semesterIds.single() <= 0L) {
            return "未选择有效学期，无法保存课程"
        }
        if (semester == null || semester.id != semesterIds.single()) {
            return "目标学期不存在或已被删除，请重新选择学期"
        }
        return null
    }
}
