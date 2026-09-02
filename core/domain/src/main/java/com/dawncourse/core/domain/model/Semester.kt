package com.dawncourse.core.domain.model

/**
 * 学期实体类
 *
 * 表示一个具体的学期信息，包含学期名称、开始日期、周数等。
 *
 * @property id 学期唯一标识符
 * @property name 学期名称（如 "2023-2024秋季学期"）
 * @property startDate 学期开始日期的时间戳（毫秒），通常为第一周周一的 00:00
 * @property weekCount 学期总周数，默认为 20 周
 * @property isCurrent 是否为当前激活的学期
 */
data class Semester(
    val id: Long = 0,
    /** 所属课表 Profile；v1/v2 旧备份缺失时由导入门禁补齐。 */
    val profileId: Long,
    /** 学期名称（如 "2023秋"） */
    val name: String,
    /** 学期开始日期的时间戳（毫秒），通常为第一周周一的 00:00 */
    val startDate: Long,
    /** 学期总周数，默认为 20 周 */
    val weekCount: Int = 20,
    /** 仅用于读取旧备份；v6 起当前学期事实存于 Profile.activeSemesterId。 */
    val isCurrent: Boolean = false
)
