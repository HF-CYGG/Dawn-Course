package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receiver 根协程必须封口非取消异常并始终 finish。 */
class ReceiverFailureBoundaryContractTest {
    @Test
    fun `Reminder 与 Silence 均保留取消语义和脱敏异常日志`() {
        listOf("ReminderReceiver.kt", "SilenceReceiver.kt").forEach { fileName ->
            val source = File("src/main/java/com/dawncourse/feature/timetable/notification/$fileName").readText()
            assertTrue(source.contains("catch (cancellation: CancellationException)"))
            assertTrue(source.contains("throw cancellation"))
            assertTrue(source.contains("catch (failure: Throwable)"))
            assertTrue(source.contains("failure.javaClass.simpleName"))
            assertTrue(source.contains("pendingResult.finish()"))
        }
    }
}
