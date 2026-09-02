package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile 切换后，旧提醒和旧 MUTE 必须在 Receiver 最终校验阶段被忽略。 */
class ProfileScopedReceiverContractTest {

    @Test
    fun `提醒与静音的最终副作用通过活动课表动作门线性化`() {
        listOf("ReminderReceiver.kt", "SilenceReceiver.kt").forEach { fileName ->
            val source = source(fileName)
            assertTrue(source.contains("activeTimetableActionGate"))
            assertTrue(source.contains("executeIfActive("))
            assertTrue(source.contains("activeContext.semester"))
        }
        assertTrue(source("ReminderReceiver.kt").contains("NotificationHelper.showCourseReminder"))
        assertTrue(source("SilenceReceiver.kt").contains("muteForSession"))
    }

    @Test
    fun `unmute 仍走已持有责任而不依赖活动 Profile`() {
        val source = source("SilenceReceiver.kt")
        assertTrue(source.contains("TriggerKind.UNMUTE -> recoverOwnedSession"))
        assertTrue(source.contains("key.profileId == TriggerKey.LEGACY_PROFILE_ID"))
    }

    private fun source(fileName: String): String = File(
        "src/main/java/com/dawncourse/feature/timetable/notification/$fileName"
    ).readText()
}
