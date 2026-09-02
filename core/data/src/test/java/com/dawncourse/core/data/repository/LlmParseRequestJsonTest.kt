package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.SanitizedDiagnosticSample
import com.google.gson.JsonParser
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 云端解析请求不得携带原文或本地 Profile 身份。 */
class LlmParseRequestJsonTest {
    @Test
    fun encode_containsOnlySanitizedContentAndImportSessionIdentity() {
        val content = "姓名：***"
        val sample = SanitizedDiagnosticSample(
            importSessionId = "11111111-1111-4111-8111-111111111111",
            sanitizerVersion = 1,
            contentSha256 = sha256(content),
            content = content
        )

        val json = JsonParser.parseString(LlmParseRequestJson.encode(
            sample = sample,
            consentAt = 1_800_000_000_000L,
            schoolId = "school",
            schoolName = "某大学",
            schoolSystemType = "zhengfang",
            sourceUrl = "https://student:secret@example.edu.cn:8443/kb?token=must-not-upload#private",
            scriptName = "zf",
            scriptVersion = 1,
            scriptSource = "remote",
            failureType = "parser_empty",
            clientVersion = "android/test",
            issueId = null,
            attemptedParsers = listOf("zf")
        )).asJsonObject

        assertEquals("姓名：***", json.get("sanitizedContent").asString)
        assertEquals(sample.importSessionId, json.get("importSessionId").asString)
        assertEquals(sample.contentSha256, json.get("contentSha256").asString)
        assertEquals(sample.sanitizerVersion, json.get("sanitizerVersion").asInt)
        assertTrue(json.get("userConsent").asBoolean)
        assertEquals("https://example.edu.cn:8443", json.get("sourceUrl").asString)
        assertFalse(json.toString().contains("must-not-upload"))
        assertFalse(json.toString().contains("student"))
        assertFalse(json.toString().contains("secret"))
        assertFalse(json.toString().contains("private"))
        assertFalse(json.has("profileId"))
        assertFalse(json.has("content"))
        assertFalse(json.has("html"))
        assertFalse(json.has("rawHtml"))
        assertFalse(json.has("parseSessionId"))
    }

    /** 结构正确但与内容不匹配的 hash 必须在网络编码前拒绝。 */
    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsMismatchedSanitizedContentHash() {
        LlmParseRequestJson.encode(
            sample = SanitizedDiagnosticSample(
                importSessionId = "11111111-1111-4111-8111-111111111111",
                sanitizerVersion = 1,
                contentSha256 = sha256("different content"),
                content = "姓名：***"
            ),
            consentAt = 1_800_000_000_000L,
            schoolId = null,
            schoolName = null,
            schoolSystemType = null,
            sourceUrl = null,
            scriptName = null,
            scriptVersion = null,
            scriptSource = null,
            failureType = null,
            clientVersion = null,
            issueId = null,
            attemptedParsers = emptyList()
        )
    }

    /** URL 策略仅保留 scheme、host 与非默认端口。 */
    @Test
    fun originOnly_stripsUserInfoPathQueryAndFragment() {
        assertEquals(
            "https://example.edu.cn:8443",
            DiagnosticUrlPolicy.originOnly("https://user:password@example.edu.cn:8443/path?a=token#secret")
        )
        assertEquals("https://example.edu.cn", DiagnosticUrlPolicy.originOnly("https://example.edu.cn:443/path"))
        assertEquals("http://example.edu.cn", DiagnosticUrlPolicy.originOnly("http://example.edu.cn:80/path"))
        assertEquals("", DiagnosticUrlPolicy.originOnly("file:///private/page.html"))
        assertEquals("", DiagnosticUrlPolicy.originOnly("not a url"))
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
