package com.dawncourse.feature.import_module

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断 HTML 脱敏器的隐私回归测试。 */
class DiagnosticHtmlSanitizerTest {
    /** 常见教务 PII 与敏感表单值不得进入脱敏结果。 */
    @Test
    fun sanitize_removesCommonPersonalInformationAndKeepsStructure() {
        val rawHtml = """
            <html>
              <head><style>.secret { color: red; }</style><script>window.token='secret-token'</script></head>
              <body>
                <p>学号：2026123456</p>
                <p>姓名：张三</p>
                <p>手机：13812345678</p>
                <p>身份证：11010519491231002X</p>
                <p>邮箱：student@example.edu.cn</p>
                <pre>{"email":"student@example.edu.cn"}</pre>
                <input name="xh" value="2026123456" />
                <input id="xm" value="张三" />
                <input name="password" value="p'ass\\word" />
                <table><tr><th>姓名：王五</th><th>学号：2026999999</th><th>手机：&#49;&#51;&#57;&#48;&#48;&#48;&#48;&#49;&#49;&#49;&#49;</th></tr><tr><td>高数</td><td>A101</td></tr></table>
                <p>邮箱：encoded&#64;example.edu.cn</p>
              </body>
            </html>
        """.trimIndent()

        val sanitized = DiagnosticHtmlSanitizer.sanitize(rawHtml)

        listOf(
            "2026123456",
            "2026999999",
            "张三",
            "王五",
            "13812345678",
            "13900001111",
            "11010519491231002X",
            "student@example.edu.cn",
            "encoded@example.edu.cn",
            "p'ass\\word",
            "secret-token"
        ).forEach { secret -> assertFalse("脱敏结果泄漏：$secret", sanitized.content.contains(secret)) }
        assertTrue(sanitized.content.contains("<table"))
        assertTrue(sanitized.content.contains("{\"email\":\"***\"}"))
        assertTrue(sanitized.content.contains("[TIMETABLE_STRUCTURE]"))
        assertFalse(sanitized.content.substringAfter("[TIMETABLE_STRUCTURE]").contains("王五"))
        assertFalse(sanitized.content.substringAfter("[TIMETABLE_STRUCTURE]").contains("2026999999"))
        assertTrue(sanitized.content.contains("table_1_header_count=3"))
        assertFalse(sanitized.content.contains("&#49;"))
        assertFalse(sanitized.content.contains("&#64;"))
        assertTrue(sanitized.contentSha256.matches(Regex("[a-f0-9]{64}")))
    }

    /** 默认导出函数只能返回脱敏内容，不能回传原始 HTML。 */
    @Test
    fun buildSanitizedDiagnosticExport_neverReturnsRawHtml() {
        val rawHtml = "<p>姓名：李四 手机：13900001111 邮箱：li.si@example.com</p>"

        val exported = buildSanitizedDiagnosticExport(rawHtml)

        assertFalse(exported.contains("李四"))
        assertFalse(exported.contains("13900001111"))
        assertFalse(exported.contains("li.si@example.com"))
        assertTrue(exported.contains("***"))
    }
}
