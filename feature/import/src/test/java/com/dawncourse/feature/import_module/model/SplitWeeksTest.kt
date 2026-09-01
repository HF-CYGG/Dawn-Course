package com.dawncourse.feature.import_module.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [splitWeeks] / [buildWeekRange] 的周次拆分测试。
 *
 * 重点覆盖 issue #109：只上一周的课（weeks = [N]）必须被拆成
 * WeekRange(N, N, weekType = 0)，而不是被丢弃或产生非法区间。
 */
class SplitWeeksTest {

    @Test
    fun `单周 - 单元素数组拆成闭区间 N to N`() {
        assertEquals(
            listOf(WeekRange(5, 5, 0)),
            splitWeeks(listOf(5))
        )
    }

    @Test
    fun `单周 - 重复的同一周去重后仍是单区间`() {
        assertEquals(
            listOf(WeekRange(5, 5, 0)),
            splitWeeks(listOf(5, 5))
        )
    }

    @Test
    fun `连续周 - 1到3合并为一个全周区间`() {
        assertEquals(
            listOf(WeekRange(1, 3, 0)),
            splitWeeks(listOf(1, 2, 3))
        )
    }

    @Test
    fun `单周奇数周 - 1到5步长2识别为单周`() {
        assertEquals(
            listOf(WeekRange(1, 5, 1)),
            splitWeeks(listOf(1, 3, 5))
        )
    }

    @Test
    fun `连续区间后跟一个孤立单周 - 拆成两个区间`() {
        assertEquals(
            listOf(WeekRange(1, 3, 0), WeekRange(7, 7, 0)),
            splitWeeks(listOf(1, 2, 3, 7))
        )
    }

    @Test
    fun `空数组 - 返回空列表`() {
        assertEquals(emptyList<WeekRange>(), splitWeeks(emptyList()))
    }

    @Test
    fun `乱序输入 - 排序后正确拆分`() {
        assertEquals(
            listOf(WeekRange(3, 3, 0), WeekRange(9, 9, 0)),
            splitWeeks(listOf(9, 3))
        )
    }
}
