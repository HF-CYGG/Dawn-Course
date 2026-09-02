package com.dawncourse.feature.import_module.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ScriptRuntimeExecutionGateTest {

    @Test
    fun `未知远端失败映射为固定脱敏错误`() {
        val result = ScriptEngine.unexpectedRuntimeFailureResult()

        assertEquals(ScriptEngine.ERROR_SCRIPT_EXCEPTION, result.errorCode)
        assertEquals("script runtime failed", result.errorMessage)
    }

    @Test
    fun `连接先于取消时提交执行且取消返回远端 PID`() {
        val gate = ScriptRuntimeExecutionGate()

        assertTrue(gate.onConnected(101).shouldSubmit)
        assertEquals(101, gate.cancelAndGetProcessId())
        assertTrue(gate.isCancelled())
    }

    @Test
    fun `取消先于延迟连接时不提交并回收远端 PID`() {
        val gate = ScriptRuntimeExecutionGate()

        assertEquals(0, gate.cancelAndGetProcessId())
        val decision = gate.onConnected(202)

        assertFalse(decision.shouldSubmit)
        assertEquals(202, decision.processIdToReclaim)
    }

    @Test
    fun `旧版脚本 IPC 文件仅清理精确目录中的精确文件名`() {
        val cacheDirectory = Files.createTempDirectory("dawn-script-runtime-test").toFile()
        try {
            val legacyDirectory = File(cacheDirectory, "script_runtime").apply { mkdirs() }
            val request = File(legacyDirectory, "request-old.json").apply { writeText("raw html") }
            val response = File(legacyDirectory, "response-old.json").apply { writeText("raw html") }
            val unrelated = File(legacyDirectory, "keep.json").apply { writeText("keep") }
            val nested = File(legacyDirectory, "nested").apply { mkdirs() }
            val nestedRequest = File(nested, "request-nested.json").apply { writeText("keep") }

            LegacyScriptRuntimeFileCleanup.clear(cacheDirectory)

            assertFalse(request.exists())
            assertFalse(response.exists())
            assertTrue(unrelated.exists())
            assertTrue(nestedRequest.exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }
}
