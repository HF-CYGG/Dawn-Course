package com.dawncourse.core.domain.model

/**
 * 脚本学校上下文工具。
 *
 * 统一生成客户端与服务端一致的学校标识，避免 Android 端上报、manifest 拉取、
 * 学校专属脚本绑定使用不同规则导致无法命中。
 */
object ScriptSchoolContext {
    const val PREFERENCES_NAME: String = "script_runtime"
    const val KEY_SCHOOL_ID: String = "school_id"
    const val KEY_SCHOOL_NAME: String = "school_name"
    const val KEY_SCHOOL_SYSTEM_TYPE: String = "school_system_type"

    fun buildSchoolId(
        schoolName: String,
        schoolSystemType: String,
        sourceUrl: String
    ): String {
        val normalizedSystemType = normalizeSystemType(schoolSystemType)
        val normalizedName = normalizeSchoolName(schoolName)
        val identity = normalizedName.ifBlank {
            extractHost(sourceUrl).ifBlank { "unknown" }
        }
        return "school:${normalizedSystemType.lowercase()}:$identity"
    }

    fun normalizeSystemType(value: String): String {
        val text = value.lowercase()
        return when {
            text.contains("qiang") || text.contains("强智") -> "QIANGZHI"
            text.contains("kingo") || text.contains("青果") -> "KINGOSOFT"
            text.contains("qidi") || text.contains("启迪") -> "QIDI"
            text.contains("chaoxing") || text.contains("超星") -> "CHAOXING"
            text.contains("zf") || text.contains("zheng") || text.contains("正方") -> "ZF"
            else -> "UNKNOWN"
        }
    }

    private fun normalizeSchoolName(value: String): String {
        return value.trim().replace(Regex("\\s+"), "").lowercase()
    }

    private fun extractHost(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            java.net.URI(value).host.orEmpty().lowercase()
        }.getOrDefault(
            value.removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .lowercase()
        )
    }
}
