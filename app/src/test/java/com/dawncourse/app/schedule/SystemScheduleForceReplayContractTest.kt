package com.dawncourse.app.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 系统恢复广播必须强制重放所有 Desired Alarm。 */
class SystemScheduleForceReplayContractTest {

    @Test
    fun `SystemScheduleReceiver 传入 forceReplay true`() {
        val source = File(
            "src/main/java/com/dawncourse/app/schedule/SystemScheduleReceiver.kt"
        ).readText()

        assertTrue(source.contains("goAsync()"))
        assertTrue(
            Regex("triggerImmediateWorkAndAwait\\(\\s*appContext,\\s*forceReplay\\s*=\\s*true")
                .containsMatchIn(source)
        )
        assertTrue(source.contains("pendingResult.finish()"))
    }
}
