package com.dawncourse.feature.import_module

internal fun escapeJavaScriptSingleQuoted(raw: String): String = buildString(raw.length) {
    raw.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(character)
        }
    }
}
