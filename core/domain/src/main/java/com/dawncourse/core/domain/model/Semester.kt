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
    val name: String, // e.g., "2023秋"
    val startDate: Long, // Timestamp of the first Monday 00:00
    val weekCount: Int = 20,
    val isCurrent: Boolean = false
)
