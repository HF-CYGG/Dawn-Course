package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 仅为一个发布周期保留的旧版无 URI UNMUTE 恢复桥。 */
class LegacyUnmuteUpgradePolicyTest {

    @Test
    fun `只接受严格 unmute action 且 data 为空`() {
        assertTrue(LegacyUnmuteUpgradePolicy.shouldRecover(SilenceReceiver.ACTION_UNMUTE, null))
        assertFalse(LegacyUnmuteUpgradePolicy.shouldRecover(SilenceReceiver.ACTION_MUTE, null))
        assertFalse(LegacyUnmuteUpgradePolicy.shouldRecover(ReminderReceiver.ACTION_REMINDER, null))
        assertFalse(LegacyUnmuteUpgradePolicy.shouldRecover(SilenceReceiver.ACTION_UNMUTE, ""))
        assertFalse(LegacyUnmuteUpgradePolicy.shouldRecover(SilenceReceiver.ACTION_UNMUTE, "dawn://alarm/0/1/2026-08-24/unmute"))
    }

    @Test
    fun `存在新式 active 或 pending 会话时策略拒绝桥接`() {
        assertFalse(LegacyUnmuteUpgradePolicy.shouldRecover(
            action = SilenceReceiver.ACTION_UNMUTE,
            dataUri = null,
            hasActiveOrPendingSession = true
        ))
        assertTrue(LegacyUnmuteUpgradePolicy.shouldRecover(
            action = SilenceReceiver.ACTION_UNMUTE,
            dataUri = null,
            hasActiveOrPendingSession = false
        ))
    }

    @Test
    fun `兼容桥 Receiver 保持 exported false 且带移除标记`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiverBlock = manifest.substringAfter(".notification.SilenceReceiver")
            .substringBefore("</receiver>")
        assertTrue(receiverBlock.contains("android:exported=\"false\""))
        assertTrue(receiverBlock.contains("com.dawncourse.action.UNMUTE"))
        assertTrue(LegacyUnmuteUpgradePolicy.BRIDGE_RELEASE_MARKER.isNotBlank())
    }
}
