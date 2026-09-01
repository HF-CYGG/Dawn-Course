package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receiver 应按 TriggerKey 发生日计算周次，不能以触发当天替换发生日。 */
class ReceiverOccurrenceDateContractTest {
    @Test
    fun `Reminder 支持跨日前提醒且上课后由窗口策略拒绝`() {
        val source = source("ReminderReceiver.kt")
        assertTrue(source.contains("key.occurrenceDate.atStartOfDay(zoneId)"))
        assertTrue(source.contains("TriggerOccurrencePolicy.isInReminderWindow"))
        assertFalse(source.contains("key.occurrenceDate != today"))
    }

    @Test
    fun `Mute 按 occurrenceDate 校验跨午夜课程窗口`() {
        val source = source("SilenceReceiver.kt")
        assertTrue(source.contains("key.occurrenceDate.atStartOfDay(zoneId)"))
        assertTrue(source.contains("occurrenceDate = key.occurrenceDate"))
        assertFalse(source.contains("key.occurrenceDate != today"))
    }

    @Test
    fun `SilenceHelper 不直接构造 Store 且 Receiver 从 Hilt 获取单例 helper`() {
        assertFalse(source("SilenceHelper.kt").contains("AppMuteSessionStore("))
        assertTrue(source("SilenceReceiver.kt").contains("fun silenceHelper(): SilenceHelper"))
    }

    private fun source(fileName: String): String = File(
        "src/main/java/com/dawncourse/feature/timetable/notification/$fileName"
    ).readText()
}
