package com.dawncourse.feature.import_module.model

/**
 * 导入解析结果模型
 *
 * JS 脚本解析后返回的 JSON 结构应对应此数据类。
 * 用于中间层传输，最终会转换为 Core:Domain 中的 Course 实体。
 */
data class ImportResult(
    val courses: List<ParsedCourse>,
    val error: String? = null
)

/**
 * 解析后的课程实体 (DTO)
 */
data class ParsedCourse(
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int, // 1-7
    val startSection: Int,
    val duration: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int // 0=All, 1=Odd, 2=Even
)
