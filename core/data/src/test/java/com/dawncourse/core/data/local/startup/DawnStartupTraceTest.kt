package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
                "begin:Dawn#FileLock",
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

    @Test
    fun asyncSectionPairsSameLabelAndCookieAcrossDispatcher() = runBlocking {
        val events = mutableListOf<String>()
        val cookie = 47

        val result = DawnStartupTrace.asyncSection(
            label = DawnStartupTrace.RESOLVE_PROFILE_SELECTION,
            cookie = cookie,
            sdkInt = 29,
            beginAsyncSection = { label, traceCookie -> events += "begin:$label:$traceCookie" },
            endAsyncSection = { label, traceCookie -> events += "end:$label:$traceCookie" },
        ) {
            withContext(Dispatchers.Default) { "业务结果" }
        }

        assertEquals("业务结果", result)
        assertEquals(
            listOf(
                "begin:Dawn#ResolveProfileSelection:47",
                "end:Dawn#ResolveProfileSelection:47",
            ),
            events,
        )
    }

    @Test
    fun asyncSectionKeepsBusinessFailureWhenTraceEndFails() = runBlocking {
        val failure = runCatching {
            DawnStartupTrace.asyncSection(
                label = DawnStartupTrace.RESOLVE_PROFILE_SELECTION,
                cookie = 48,
                sdkInt = 29,
                beginAsyncSection = { _, _ -> Unit },
                endAsyncSection = { _, _ -> error("Trace end 失败") },
            ) {
                throw IllegalArgumentException("业务失败")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("业务失败", failure?.message)
    }

    @Test
    fun asyncSectionSkipsAsyncTraceOnApi28AndStillRunsBusinessBlock() = runBlocking {
        val result = DawnStartupTrace.asyncSection(
            label = DawnStartupTrace.RESOLVE_PROFILE_SELECTION,
            cookie = 49,
            sdkInt = 28,
            beginAsyncSection = { _, _ -> error("API 28 不得调用 beginAsyncSection") },
            endAsyncSection = { _, _ -> error("API 28 不得调用 endAsyncSection") },
        ) {
            "业务结果"
        }

        assertEquals("业务结果", result)
    }
}
