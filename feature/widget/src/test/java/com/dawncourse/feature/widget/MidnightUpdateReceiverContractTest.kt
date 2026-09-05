package com.dawncourse.feature.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 午夜接收器只在 capability 策略守卫的平台调用处抑制权限检查。 */
class MidnightUpdateReceiverContractTest {

    @Test
    fun `AlarmManager 使用类型安全获取且服务缺失安全退出`() {
        val source = receiverSource()

        assertTrue(source.contains("getSystemService(AlarmManager::class.java) ?: return"))
    }

    @Test
    fun `MissingPermission suppression 只覆盖 exact 平台适配函数`() {
        val source = receiverSource()

        assertEquals(1, source.windowed("@SuppressLint(\"MissingPermission\")".length)
            .count { candidate -> candidate == "@SuppressLint(\"MissingPermission\")" })
        assertTrue(
            Regex("@SuppressLint\\(\\\"MissingPermission\\\"\\)\\s+private fun scheduleExact")
                .containsMatchIn(source),
        )
        assertTrue(source.contains("MidnightAlarmStrategy.schedule"))
        assertTrue(source.contains("capability 策略已在调用前确认精确闹钟权限"))
    }

    @Test
    fun `取消路径只封口普通 Exception 而不吞 Error`() {
        val source = receiverSource()

        assertTrue(source.contains("catch (_: Exception)"))
        assertTrue(!source.contains("catch (_: Throwable)"))
    }

    /** 读取待验证的生产接收器源码。 */
    private fun receiverSource(): String = File(
        "src/main/java/com/dawncourse/feature/widget/MidnightUpdateReceiver.kt",
    ).readText()
}
