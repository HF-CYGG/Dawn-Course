package com.dawncourse.feature.import_module

import java.security.MessageDigest
import org.jsoup.parser.Parser

/** 脱敏后的诊断 HTML 与完整性元数据。 */
data class SanitizedHtmlResult(
    /** 脱敏规则版本。 */
    val sanitizerVersion: Int,
    /** 脱敏内容 SHA-256。 */
    val contentSha256: String,
    /** 仅保留诊断所需页面结构的脱敏内容。 */
    val content: String
)

/**
 * 教务页面诊断内容的唯一受信脱敏器。
 *
 * 该实现由原 `ImportViewModel.sanitizeHtmlForLlm` 原样抽出并补齐敏感表单字段，
 * 云端上传、应用私有诊断副本和用户手动导出必须共用这一入口。
 */
object DiagnosticHtmlSanitizer {
    /** 当前脱敏规则版本。 */
    const val VERSION = 3

    /** 单次可处理的原始页面上限，避免恶意页面造成过量内存占用。 */
    const val MAX_INPUT_BYTES = 4 * 1024 * 1024

    /** 将原始 HTML/文本转换为不含常见 PII 的诊断副本。 */
    fun sanitize(raw: String): SanitizedHtmlResult {
        val rawBytes = raw.toByteArray(Charsets.UTF_8)
        require(rawBytes.size <= MAX_INPUT_BYTES) { "diagnostic input exceeds size limit" }

        // 先解码实体，避免手机号、邮箱等通过 `&#...;` / `&commat;` 绕过文本规则。
        var text = Parser.unescapeEntities(raw, false)
        text = text.replace(SCRIPT_PATTERN, "<script>***</script>")
        text = text.replace(STYLE_PATTERN, "<style>***</style>")
        text = text.replace(INPUT_TAG_PATTERN) { match -> sanitizeSensitiveInputTag(match.value) }
        text = text.replace(SECRET_ASSIGNMENT_PATTERN, "$1=\"***\"")
        text = text.replace(TOKEN_PAIR_PATTERN, "$1=***")
        text = text.replace(STRUCTURED_PII_PAIR_PATTERN, "$1$2***$3")
        text = text.replace(STUDENT_NUMBER_PATTERN, "$1$2***")
        text = text.replace(NAME_PATTERN, "$1$2***")
        text = text.replace(ID_CARD_PATTERN, "******************")
        text = text.replace(MOBILE_PATTERN, "***********")
        text = text.replace(EMAIL_PATTERN, "***@***")

        // 摘要必须从已经脱敏的内容生成，禁止把 header 中的 PII 从 raw 再次引回结果。
        val structuralSummary = buildTimetableStructureSummary(text)
        val content = if (structuralSummary.isBlank()) text else "$text\n\n$structuralSummary"
        return SanitizedHtmlResult(
            sanitizerVersion = VERSION,
            contentSha256 = sha256(content),
            content = content
        )
    }

    /**
     * 清空敏感 input 的 value，保留标签结构和其它属性。
     *
     * 触发条件：
     * - 任意 `type=hidden` / `type=password` 的 input —— CAS/SSO 登录页失败时，
     *   csrf、ticket、lt、execution、SAMLResponse 等一次性票据都藏在 hidden 里，
     *   它们对课表解析诊断没有价值，一律清空；
     * - 名称/ID/autocomplete 命中身份或认证字段白名单的 input（覆盖非 hidden 的情况）。
     *
     * 同时覆盖带引号与不带引号两种 value 写法。
     */
    private fun sanitizeSensitiveInputTag(tag: String): String {
        val sensitive = HIDDEN_OR_PASSWORD_INPUT_PATTERN.containsMatchIn(tag) ||
            SENSITIVE_INPUT_MARKER_PATTERN.containsMatchIn(tag)
        if (!sensitive) return tag
        val quotedRedacted = tag.replace(INPUT_VALUE_PATTERN) { match ->
            val prefix = match.groupValues[1]
            val quote = match.groupValues[2]
            "$prefix$quote***$quote"
        }
        return quotedRedacted.replace(INPUT_VALUE_UNQUOTED_PATTERN, "$1\"***\"")
    }

    /** 构建不含单元格正文的课表结构摘要。 */
    private fun buildTimetableStructureSummary(raw: String): String {
        val tableMatches = TABLE_PATTERN.findAll(raw).toList()
        if (tableMatches.isEmpty()) return ""
        val lines = mutableListOf("[TIMETABLE_STRUCTURE]")
        tableMatches.take(MAX_TABLE_SUMMARIES).forEachIndexed { index, match ->
            val tableHtml = match.groupValues.getOrNull(1).orEmpty()
            val rowCount = TABLE_ROW_PATTERN.findAll(tableHtml).count()
            val headerCount = TABLE_HEADER_PATTERN.findAll(tableHtml).take(MAX_TABLE_HEADERS).count()
            lines.add("table_${index + 1}: rows=$rowCount")
            if (headerCount > 0) {
                // Header 正文不是解析器诊断所必需；只保留数量，避免任何未知编码重引入 PII。
                lines.add("table_${index + 1}_header_count=$headerCount")
            }
        }
        return lines.joinToString("\n")
    }

    /** 计算小写十六进制 SHA-256。 */
    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val MAX_TABLE_SUMMARIES = 4
    private const val MAX_TABLE_HEADERS = 12
    private val SCRIPT_PATTERN = Regex("(?is)<script\\b[^>]*>(.*?)</script>")
    private val STYLE_PATTERN = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
    private val INPUT_TAG_PATTERN = Regex("(?is)<input\\b[^>]*>")
    /** hidden / password 类型的 input，无论字段名如何一律清空 value。 */
    private val HIDDEN_OR_PASSWORD_INPUT_PATTERN = Regex(
        "(?i)\\btype\\s*=\\s*['\"]?(?:hidden|password)['\"]?"
    )
    private val SENSITIVE_INPUT_MARKER_PATTERN = Regex(
        // name/id/autocomplete 的取值只要“包含”下列关键词即视为敏感（覆盖 csrf_token、
        // loginToken、org.apache.struts...TOKEN 等变体）。诊断样本丢一个 input 的 value
        // 不影响结构诊断，宁可多脱敏。
        "(?i)(?:name|id|autocomplete)\\s*=\\s*['\"][^'\"]*(?:" +
            // 身份类
            "xh|xgh|student|username|user|account|xm|realname|full_?name|" +
            "mobile|phone|tel|sfzh|idcard|identity|email|password|passwd|pwd|mm|hidmm|" +
            // 认证 / 反跨站 / SSO 票据类
            "csrf|xsrf|requestverificationtoken|authenticity_token|token|ticket|tgt|" +
            "execution|_eventid|saml|relaystate|rsakey|encryptsalt|encrypted|croypto|" +
            "logindata|viewstate|eventvalidation" +
            ")[^'\"]*['\"]"
    )
    private val INPUT_VALUE_PATTERN = Regex("(?is)(\\bvalue\\s*=\\s*)(['\"])(.*?)\\2")
    /** 无引号写法 value=xxx（CAS/正方偶见），把 xxx 换成带引号的 *** 。 */
    private val INPUT_VALUE_UNQUOTED_PATTERN = Regex("(?is)(\\bvalue\\s*=\\s*)(?![\"'])[^\\s>]+")
    private val SECRET_ASSIGNMENT_PATTERN = Regex(
        "(?i)(password|passwd|pwd|mm|hidMm|token|csrf|session(?:id)?|ticket|lt|execution|saml\\w+|relaystate|authenticity_token)\\s*=\\s*['\"][^'\"]{1,}['\"]"
    )
    private val TOKEN_PAIR_PATTERN = Regex(
        "(?i)(token|csrf|session(?:id)?)\\s*[:=]\\s*['\"]?[A-Za-z0-9_\\-\\.]{6,}['\"]?"
    )
    private val STRUCTURED_PII_PAIR_PATTERN = Regex(
        "(?i)(student(?:id|no|number)?|username|account|realname|full_?name|mobile|phone|tel|idcard|identity|email)" +
            "(\\s*['\"]?\\s*[:=]\\s*['\"])[^'\"<>{}\\r\\n]{2,}(['\"]?)"
    )
    private val STUDENT_NUMBER_PATTERN = Regex("(?i)(学号|学籍号|账号|用户名|用户号)(\\s*[:：]?\\s*)\\w{4,}")
    private val NAME_PATTERN = Regex("(?i)(姓名)(\\s*[:：]?\\s*)[\\u4e00-\\u9fa5A-Za-z·\\s]{2,}")
    private val ID_CARD_PATTERN = Regex("\\b\\d{17}[0-9Xx]\\b")
    private val MOBILE_PATTERN = Regex("\\b1[3-9]\\d{9}\\b")
    private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val TABLE_PATTERN = Regex("(?is)<table\\b[^>]*>(.*?)</table>")
    private val TABLE_ROW_PATTERN = Regex("(?is)<tr\\b")
    private val TABLE_HEADER_PATTERN = Regex("(?is)<th\\b[^>]*>(.*?)</th>")
}

/** 用户手动导出诊断副本时也只允许使用共享脱敏器的结果。 */
fun buildSanitizedDiagnosticExport(raw: String): String = DiagnosticHtmlSanitizer.sanitize(raw).content
