package com.dawncourse.feature.import_module.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportModelsDepthTest {
    @Test
    fun `允许上限内的字符串包装结果`() {
        var raw = validCoursePayload()
        repeat(4) {
            raw = JSONObject().put("payload", raw).toString()
        }

        assertEquals(1, parseParsedCoursesFromRaw(raw).size)
    }

    @Test
    fun `拒绝超过上限的字符串包装结果`() {
        var raw = validCoursePayload()
        repeat(5) {
            raw = JSONObject().put("payload", raw).toString()
        }

        assertTrue(parseParsedCoursesFromRaw(raw).isEmpty())
    }

    @Test
    fun `拒绝超过上限的对象包装结果`() {
        var nested: Any = JSONObject(validCoursePayload())
        repeat(5) {
            nested = JSONObject().put("result", nested)
        }

        assertTrue(parseParsedCoursesFromRaw(nested.toString()).isEmpty())
    }

    @Test
    fun `允许上限内的对象与字符串混合包装结果`() {
        var nested: Any = JSONObject(validCoursePayload())
        repeat(2) {
            nested = JSONObject().put("data", nested)
        }
        val raw = JSONObject()
            .put("payload", JSONObject().put("result", nested).toString())
            .toString()

        assertEquals(1, parseParsedCoursesFromRaw(raw).size)
    }

    private fun validCoursePayload(): String = JSONObject()
        .put(
            "courses",
            JSONArray().put(
                JSONObject()
                    .put("name", "测试课程")
                    .put("teacher", "")
                    .put("location", "")
                    .put("dayOfWeek", 1)
                    .put("startSection", 1)
                    .put("duration", 2)
                    .put("startWeek", 1)
                    .put("endWeek", 1)
                    .put("weekType", 0)
            )
        )
        .toString()
}
