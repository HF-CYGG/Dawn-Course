package com.dawncourse.core.ui.util

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
        "#E8DEF8", // 浅紫
        "#F2E7FE",
        "#C4E7FF", // 浅蓝
        "#C3EED0", // 浅绿
        "#FDE2E4", // 浅粉
        "#FFF4DE", // 浅黄
        "#D7E8CD", // 莫兰迪绿
        "#EAD5D5", // 莫兰迪粉
        "#D8E2DC", // 莫兰迪青
        "#FFE5D9", // 莫兰迪橙
        "#ECE4DB"  // 莫兰迪灰
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
        return generateColor(course.name, course.teacher)
    }

    /**
     * 根据名称和教师生成颜色 (用于导入预览等没有完整 Course 对象的场景)
     */
    fun generateColor(name: String, teacher: String?): String {
        val hash = abs((name + (teacher ?: "")).hashCode())
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
