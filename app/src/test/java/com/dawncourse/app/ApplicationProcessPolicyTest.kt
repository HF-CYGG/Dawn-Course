package com.dawncourse.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Application 多进程初始化门禁的纯 JVM 测试。 */
class ApplicationProcessPolicyTest {

    @Test
    fun `主进程名称与包名一致时允许初始化系统能力`() {
        assertTrue(
            ApplicationProcessPolicy.shouldInitializeSystemSurfaces(
                packageName = "com.dawncourse.app",
                processName = "com.dawncourse.app"
            )
        )
    }

    @Test
    fun `脚本运行时进程不得初始化 Widget 或 WorkManager`() {
        assertFalse(
            ApplicationProcessPolicy.shouldInitializeSystemSurfaces(
                packageName = "com.dawncourse.app",
                processName = "com.dawncourse.app:script_runtime"
            )
        )
    }

    @Test
    fun `空或未知进程名称保守跳过系统能力初始化`() {
        listOf(null, "", "   ", "unknown.process").forEach { processName ->
            assertFalse(
                ApplicationProcessPolicy.shouldInitializeSystemSurfaces(
                    packageName = "com.dawncourse.app",
                    processName = processName
                )
            )
        }
        assertFalse(
            ApplicationProcessPolicy.shouldInitializeSystemSurfaces(
                packageName = "",
                processName = "com.dawncourse.app"
            )
        )
    }

    @Test
    fun `proc cmdline fallback 去除结尾空字节并拒绝空值`() {
        val processName = ApplicationProcessPolicy.decodeProcCmdline(
            "com.dawncourse.app:script_runtime\u0000ignored".toByteArray()
        )

        assertEquals("com.dawncourse.app:script_runtime", processName)
        assertNull(ApplicationProcessPolicy.decodeProcCmdline(byteArrayOf(0, 0)))
        assertNull(ApplicationProcessPolicy.decodeProcCmdline(byteArrayOf()))
    }
}
