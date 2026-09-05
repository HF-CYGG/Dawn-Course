package com.dawncourse.feature.import_module

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 锁定教务登录与验证码 WebView 的本地内容访问边界。 */
class QidiWebViewSecurityContractTest {
    /** 两个 WebView 都必须使用 CodeQL 可识别的显式 API 禁止 content:// 访问。 */
    @Test
    fun everyWebViewExplicitlyDisablesContentAccess() {
        val source = sourceFile("QidiAutoSyncScreen.kt")
        val explicitDisableCount = Regex("""settings\.setAllowContentAccess\(false\)""")
            .findAll(source)
            .count()

        assertEquals(2, explicitDisableCount)
        assertFalse(source.contains("settings.allowContentAccess = true"))
    }

    /** 读取当前模块源码；兼容从仓库根或模块目录启动测试。 */
    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/dawncourse/feature/import_module/$name"),
            File("feature/import/src/main/java/com/dawncourse/feature/import_module/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("找不到源码：$name")
    }
}
