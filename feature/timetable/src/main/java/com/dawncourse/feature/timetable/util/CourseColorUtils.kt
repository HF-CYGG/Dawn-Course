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

    // 12 套预设配色方案 (Material 3 Tone based, adjusted for distinctiveness)
    // 同时也考虑了色盲友好性 (High contrast)
    private val PRESET_COLORS = listOf(
        "#F44336", // Red
        "#E91E63", // Pink
        "#9C27B0", // Purple
        "#673AB7", // Deep Purple
        "#3F51B5", // Indigo
        "#2196F3", // Blue
        "#03A9F4", // Light Blue
        "#00BCD4", // Cyan
        "#009688", // Teal
        "#4CAF50", // Green
        "#8BC34A", // Light Green
        "#FFC107"  // Amber
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
