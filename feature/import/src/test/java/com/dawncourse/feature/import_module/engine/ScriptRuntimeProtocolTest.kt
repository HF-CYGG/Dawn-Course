package com.dawncourse.feature.import_module.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRuntimeProtocolTest {
    @Test
    fun `脚本结果协议往返保留脱敏诊断`() {
        val source = ScriptEngine.ScriptExecutionResult(
            raw = "[]",
            ok = true,
            schemaValid = true,
            resultCount = 1,
            errorCode = "",
            errorMessage = "",
            entryUsed = "parse",
            contractVersion = 1,
            diagnostics = listOf("no_weeks", "no_sections")
        )

        assertEquals(source, scriptExecutionResultFromJson(source.toProtocolJson()))
    }

    @Test
    fun `旧协议缺少诊断字段时保持兼容`() {
        val legacy = baseResultJson().toString()

        assertTrue(scriptExecutionResultFromJson(legacy).diagnostics.isEmpty())
    }

    @Test
    fun `协议入站过滤未知诊断并限制最大条数`() {
        val diagnostics = JSONArray().put("private-html")
        repeat(600) { diagnostics.put("no_weeks") }
        val raw = baseResultJson().put("diagnostics", diagnostics).toString()

        val decoded = scriptExecutionResultFromJson(raw).diagnostics

        assertEquals(511, decoded.size)
        assertTrue(decoded.all { it == "no_weeks" })
    }

    private fun baseResultJson(): JSONObject = JSONObject()
        .put("raw", "[]")
        .put("ok", true)
        .put("schemaValid", true)
        .put("resultCount", 1)
        .put("errorCode", "")
        .put("errorMessage", "")
        .put("entryUsed", "parse")
        .put("contractVersion", 1)
}
