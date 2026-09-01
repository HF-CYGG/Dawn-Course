package com.dawncourse.feature.import_module

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入 UI/ViewModel 不得绕过共享脱敏与显式原文授权边界。 */
class ImportDiagnosticPrivacyContractTest {
    /** 手动诊断导出必须先调用共享脱敏器，不能把 WebView 原文直接交给 SAF。 */
    @Test
    fun manualExport_usesSanitizedCopy() {
        val source = sourceFile("ImportScreen.kt")

        assertTrue(source.contains("buildSanitizedDiagnosticExport(html)"))
        assertTrue(source.contains("pendingHtmlForExport = sanitized"))
        assertFalse(source.contains("pendingHtmlForExport = html"))
    }

    /** 默认诊断落盘与 LLM 上传只接受 typed 脱敏样本。 */
    @Test
    fun viewModel_usesTypedSanitizedSampleAndHasNoRawSaveCaller() {
        val source = sourceFile("ImportViewModel.kt")

        assertTrue(source.contains("DiagnosticHtmlSanitizer.sanitize(raw)"))
        assertTrue(source.contains("diagnosticSampleRepository.saveSanitized(localDiagnosticSample)"))
        assertTrue(source.contains("sample = SanitizedDiagnosticSample("))
        assertFalse(source.contains("diagnosticSampleRepository.saveRaw("))
    }

    /** 多解析器尝试必须累计隔离运行时返回的固定诊断短码，不能依赖共享可变状态。 */
    @Test
    fun viewModel_accumulatesDiagnosticsFromEveryIsolatedExecution() {
        val source = sourceFile("ImportViewModel.kt")

        assertTrue(source.contains("val allDiagnostics = mutableListOf<String>()"))
        assertTrue(source.contains("allDiagnostics.addAll(execution.diagnostics)"))
        assertTrue(source.contains("val droppedCount = allDiagnostics.size"))
        assertFalse(source.contains("lastRunDiagnostics"))
    }

    /** 读取当前模块源码；兼容从仓库根或模块目录启动测试。 */
    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/dawncourse/feature/import_module/$name"),
            File("feature/import/src/main/java/com/dawncourse/feature/import_module/$name")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("找不到源码：$name")
    }
}
