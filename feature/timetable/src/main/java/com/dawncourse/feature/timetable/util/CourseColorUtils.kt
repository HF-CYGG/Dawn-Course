package com.dawncourse.feature.timetable.util

import androidx.compose.ui.graphics.Color
import com.dawncourse.core.domain.model.Course
import kotlin.math.abs

/**
 * 课程颜色工具类
 *
 * 实现基于 HSL 模型的颜色分配算法，确保相邻课程颜色差异明显。
 */
object CourseColorUtils {

    // 12 套预设配色方案 (Macaron / Morandi Style)
    // 低饱和度、高明度，视觉舒适
    private val PRESET_COLORS = listOf(
        "#F48FB1", // Pink
        "#CE93D8", // Purple
        "#B39DDB", // Deep Purple
        "#9FA8DA", // Indigo
        "#90CAF9", // Blue
        "#81D4FA", // Light Blue
        "#80CBC4", // Teal
        "#A5D6A7", // Green
        "#C5E1A5", // Light Green
        "#E6EE9C", // Lime
        "#FFF59D", // Yellow
        "#FFCC80", // Orange
        "#BCAAA4", // Brown
        "#B0BEC5"  // Blue Grey
    )

    /**
     * 为课程获取显示颜色
     *
     * 如果课程已有自定义颜色，则使用之。
     * 否则根据课程 ID 或名称生成唯一颜色。
     */
    fun getCourseColor(course: Course): String {
        if (course.color.isNotEmpty()) {
            return course.color
        }
        // 使用 Hash 算法分配颜色
        val hash = abs((course.name + course.teacher).hashCode())
        val index = hash % PRESET_COLORS.size
        return PRESET_COLORS[index]
    }
    
    fun parseColor(colorHex: String): Color {
        return try {
            Color(android.graphics.Color.parseColor(colorHex))
        } catch (e: Exception) {
            Color(android.graphics.Color.parseColor(PRESET_COLORS[0]))
        }
    }

    /**
     * 根据背景色计算最佳文本颜色（黑或白）
     */
    fun getBestContentColor(backgroundColor: Color): Color {
        // 计算相对亮度: 0.2126*R + 0.7152*G + 0.0722*B
        val luminance = 0.2126f * backgroundColor.red + 0.7152f * backgroundColor.green + 0.0722f * backgroundColor.blue
        // 阈值设为 0.5，大于该值认为是亮色背景，使用黑色文字
        return if (luminance > 0.5f) Color.Black else Color.White
    }
}
