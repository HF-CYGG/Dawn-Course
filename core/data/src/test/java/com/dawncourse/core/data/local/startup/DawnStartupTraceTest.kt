package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Dawn 启动 Trace 的纯 JVM 配对测试。 */
class DawnStartupTraceTest {
    @Test
    fun sectionRecordsStableLabelAndAlwaysEndsAfterFailure() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            DawnStartupTrace.section(
                label = DawnStartupTrace.FILE_LOCK,
                beginSection = { label -> events += "begin:$label" },
                endSection = { events += "end" },
            ) {
                events += "work"
                throw IllegalStateException("模拟启动失败")
            }
        }

        assertEquals(
            listOf(
                "begin:DawnStartup/FileLock",
                "work",
                "end",
            ),
            events,
        )
    }

    @Test
    fun sectionDoesNotChangeBusinessResultWhenTracingCannotStart() {
        val result = DawnStartupTrace.section(
            label = DawnStartupTrace.KEYSTORE_UNSEAL,
            beginSection = { error("JVM 平台 Trace 不可用") },
            endSection = { error("未开始 Trace 时不得结束") },
        ) {
            "业务结果"
        }

        assertEquals("业务结果", result)
    }
}
