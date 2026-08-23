package com.dawncourse.feature.import_module.engine

import org.json.JSONArray
import org.json.JSONObject

internal data class ScriptRuntimeRequest(
    val script: String,
    val html: String,
    val harnessSource: String,
    val dependencies: List<String>,
    val targetType: String,
    val timeoutMillis: Long
) {
    fun toJson(): String = JSONObject()
        .put("script", script)
        .put("html", html)
        .put("harnessSource", harnessSource)
        .put("dependencies", JSONArray(dependencies))
        .put("targetType", targetType)
        .put("timeoutMillis", timeoutMillis)
        .toString()

    companion object {
        fun fromJson(raw: String): ScriptRuntimeRequest {
            val json = JSONObject(raw)
            val dependencyArray = json.optJSONArray("dependencies") ?: JSONArray()
            return ScriptRuntimeRequest(
                script = json.getString("script"),
                html = json.getString("html"),
                harnessSource = json.getString("harnessSource"),
                dependencies = buildList {
                    for (index in 0 until dependencyArray.length()) {
                        add(dependencyArray.getString(index))
                    }
                },
                targetType = json.optString("targetType", "parser"),
                timeoutMillis = ScriptRuntimeLimits.normalizeTimeout(
                    json.optLong("timeoutMillis", ScriptEngine.DEFAULT_TIMEOUT_MS)
                )
            )
        }
    }
}

internal fun ScriptEngine.ScriptExecutionResult.toProtocolJson(): String = JSONObject()
    .put("raw", raw)
    .put("ok", ok)
    .put("schemaValid", schemaValid)
    .put("resultCount", resultCount)
    .put("errorCode", errorCode)
    .put("errorMessage", errorMessage)
    .put("entryUsed", entryUsed)
    .put("contractVersion", contractVersion)
    .toString()

internal fun scriptExecutionResultFromJson(raw: String): ScriptEngine.ScriptExecutionResult {
    val json = JSONObject(raw)
    return ScriptEngine.ScriptExecutionResult(
        raw = json.optString("raw"),
        ok = json.optBoolean("ok", false),
        schemaValid = json.optBoolean("schemaValid", false),
        resultCount = json.optInt("resultCount", 0),
        errorCode = json.optString("errorCode"),
        errorMessage = json.optString("errorMessage"),
        entryUsed = json.optString("entryUsed"),
        contractVersion = json.optInt("contractVersion", 0)
    )
}
