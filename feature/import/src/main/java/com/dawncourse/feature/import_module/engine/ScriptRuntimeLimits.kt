package com.dawncourse.feature.import_module.engine

internal object ScriptRuntimeLimits {
    const val MAX_SCRIPT_BUNDLE_BYTES: Int = 512 * 1024
    const val MAX_HARNESS_BYTES: Int = 512 * 1024
    const val MAX_HTML_BYTES: Int = 3 * 1024 * 1024
    const val MAX_RESULT_BYTES: Int = 1024 * 1024
    const val MAX_TIMEOUT_MS: Long = 5_000L

    data class Validation(
        val isValid: Boolean,
        val errorCode: String = ""
    )

    fun validateInput(
        harnessBytes: Int,
        scriptAndDependencyBytes: Int,
        htmlBytes: Int,
        timeoutMillis: Long
    ): Validation = when {
        harnessBytes > MAX_HARNESS_BYTES -> Validation(false, "harness_too_large")
        scriptAndDependencyBytes > MAX_SCRIPT_BUNDLE_BYTES -> Validation(false, "script_too_large")
        htmlBytes > MAX_HTML_BYTES -> Validation(false, "html_too_large")
        timeoutMillis <= 0 -> Validation(false, "invalid_timeout")
        else -> Validation(true)
    }

    fun isResultSizeValid(resultBytes: Int): Boolean = resultBytes <= MAX_RESULT_BYTES

    fun normalizeTimeout(timeoutMillis: Long): Long = timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MS)

    fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size
}
