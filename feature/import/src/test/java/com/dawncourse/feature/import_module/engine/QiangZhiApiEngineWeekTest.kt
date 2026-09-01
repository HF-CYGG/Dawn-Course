package com.dawncourse.feature.import_module.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强智 Kotlin 引擎的周次解析测试（issue #109）。
 *
 * - parseWeekString 必须容忍 "周" / "第" / 括号后缀等非数字字符
 * - parseApiCourses 的单/双周过滤必须是非破坏性的：过滤后为空则保留原周次
 */
class QiangZhiApiEngineWeekTest {

    private val engine = QiangZhiApiEngine()

    @Test
    fun `parseWeekString - 单周各种写法`() {
        assertEquals(listOf(5), engine.parseWeekString("5"))
        assertEquals(listOf(5), engine.parseWeekString("5周"))
        assertEquals(listOf(5), engine.parseWeekString("第5周"))
        assertEquals(listOf(5), engine.parseWeekString("5周(1-2节)"))
        assertEquals(listOf(5), engine.parseWeekString("5-5周"))
    }

    @Test
    fun `parseWeekString - 整体被括号包住不能截成空串`() {
        assertEquals((1..16).toList(), engine.parseWeekString("(1-16周)"))
        assertEquals(listOf(5), engine.parseWeekString("（5周）"))
    }

    @Test
    fun `parseWeekString - 剔除方括号里的节次后缀`() {
        assertEquals((1..16).toList(), engine.parseWeekString("1-16周[03-04节]"))
        assertEquals(listOf(5), engine.parseWeekString("5周[1-2节]"))
    }

    @Test
    fun `parseWeekString - 区间与列表`() {
        assertEquals((1..16).toList(), engine.parseWeekString("1-16周"))
        assertEquals(listOf(1, 2, 3, 4, 10), engine.parseWeekString("1-4,10周"))
    }

    @Test
    fun `parseApiCourses - 偶数周的单周课被误标单周时不应丢弃`() {
        val arr = JSONArray().put(
            JSONObject().apply {
                put("kcmc", "形势与政策")
                put("xqj", 1)          // 周一
                put("jcs", "1-2节")    // 节次
                put("kkzc", "6周")     // 只上第 6 周（偶数）
                put("sjbz", "1")       // 数据里被误标为"单"
            }
        )

        val courses = engine.parseApiCourses(arr)

        assertEquals(1, courses.size)
        assertEquals("形势与政策", courses[0].name)
        assertEquals(listOf(6), courses[0].weeks)
        assertTrue(courses[0].sections.contains(1) && courses[0].sections.contains(2))
    }
}
