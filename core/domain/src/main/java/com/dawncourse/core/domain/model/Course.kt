package com.dawncourse.core.domain.model

/**
 * 课程实体类
 *
 * 表示一门具体的课程信息，包含课程基本信息、上课时间、周次安排等。
 * 此模型位于 Domain 层，是整个应用的核心业务对象，不依赖于任何 Android 框架或数据库实现。
 *
 * @property id 课程唯一标识符，默认为 0（数据库插入时自动生成）
 * @property name 课程名称
 * @property teacher 授课教师姓名
 * @property location 上课地点（教室）
 * @property dayOfWeek 星期几（1 = 周一, 7 = 周日）
 * @property startSection 开始节次（如第 1 节）
 * @property duration 持续节数（如 2 节课）
 * @property startWeek 开始周次（如第 1 周）
 * @property endWeek 结束周次（如第 16 周）
 * @property weekType 周次类型（0=全周, 1=单周, 2=双周），默认全周
 * @property color 课程卡片颜色（Hex 颜色代码），用于 UI 展示
 */
data class Course(
    val id: Long = 0,
    val semesterId: Long = 1, // Default to 1 for now
    val name: String,
    val teacher: String = "",
    val location: String = "",
    
    // 时间信息 (Time info)
    val dayOfWeek: Int, // 1 = Monday, 7 = Sunday
    val startSection: Int, // e.g., 1
    val duration: Int, // e.g., 2 (sections)
    
    // 周次信息 (Week info)
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int = WEEK_TYPE_ALL, // 0=All, 1=Odd, 2=Even
    
    val color: String = "", // Hex color code
    
    // 调课信息 (Reschedule info)
    val isModified: Boolean = false, // 是否为调课生成的记录
    val note: String = "", // 备注信息
    val originId: Long = 0 // 原始课程 ID，用于关联分裂后的记录
) {
    companion object {
        /** 周次类型：每周都上 */
        const val WEEK_TYPE_ALL = 0
        /** 周次类型：仅单周上 */
        const val WEEK_TYPE_ODD = 1
        /** 周次类型：仅双周上 */
        const val WEEK_TYPE_EVEN = 2
    }
}
