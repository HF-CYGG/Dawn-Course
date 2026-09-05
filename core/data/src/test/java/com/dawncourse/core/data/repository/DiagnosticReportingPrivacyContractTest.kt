package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断报告和 Keystore 适配器的隐私/并发源码契约。 */
class DiagnosticReportingPrivacyContractTest {
    /** 任意 classification map 不得夹带原文、本地 Profile 或其它身份字段。 */
    @Test
    fun classificationHints_useExplicitSafeAllowlist() {
        val filtered = DiagnosticReportMetadataPolicy.filterClassificationHints(
            linkedMapOf(
                "scriptName" to "zhengfang.js",
                "schoolSystemType" to "zhengfang",
                "failureType" to "parser_empty",
                "rawHtml" to "<html>姓名：张三</html>",
                "profileId" to "42",
                "installBucketIdHash" to "stable-identity"
            )
        )

        assertEquals(setOf("scriptName", "schoolSystemType", "failureType"), filtered.keys)
        assertFalse(filtered.toString().contains("张三"))
        assertFalse(filtered.toString().contains("42"))
        assertFalse(filtered.toString().contains("stable-identity"))
    }

    /** 非预期字符与超长分类值按非必要元数据静默丢弃。 */
    @Test
    fun classificationHints_dropUnsafeValues() {
        val filtered = DiagnosticReportMetadataPolicy.filterClassificationHints(
            mapOf(
                "scriptName" to "<html>secret</html>",
                "schoolSystemType" to "x".repeat(129),
                "failureType" to "parser_empty"
            )
        )

        assertEquals(mapOf("failureType" to "parser_empty"), filtered)
    }

    /** 同一 alias 的查找与首次生成必须完整位于同一个进程锁中。 */
    @Test
    fun keystoreFirstCreation_isProcessSerialized() {
        val source = sourceFile("DiagnosticSampleRepositoryImpl.kt")
        val lock = source.indexOf("synchronized(KEY_CREATION_LOCK)")
        val lookup = source.indexOf("KeyStore.getInstance", startIndex = lock)
        val generation = source.indexOf("generator.generateKey()", startIndex = lookup)
        val blockEnd = source.indexOf("\n        }\n    }", startIndex = generation)

        assertTrue(lock >= 0)
        assertTrue(lookup > lock)
        assertTrue(generation > lookup)
        assertTrue(blockEnd > generation)
    }

    /** serializer 本身必须再执行 URL origin 化，且不发送稳定安装身份。 */
    @Test
    fun parseReportSerializer_hasDefenseInDepthPrivacyBoundary() {
        val source = sourceFile("ParseReportRepositoryImpl.kt")

        assertTrue(source.contains("DiagnosticUrlPolicy.originOnly(sourceUrl)"))
        assertTrue(source.contains("DiagnosticReportMetadataPolicy.filterClassificationHints(classificationHint)"))
        assertFalse(source.contains(".put(\"installBucketIdHash\""))
        assertFalse(source.contains(".put(\"profileId\""))
        assertFalse(source.contains(".put(\"rawHtml\""))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/dawncourse/core/data/repository/$name"),
            File("core/data/src/main/java/com/dawncourse/core/data/repository/$name")
        )
        return candidates.firstOrNull(File::isFile)
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("找不到源码：$name")
    }
}
