package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定 GitHub Actions 使用 SDK Manager 实际发布的 Android 17 平台包名。 */
class AndroidCiSdkPackageContractTest {

    @Test
    fun `Android CI 的两个构建任务都安装 android 37 point 2`() {
        val workflow = File("../.github/workflows/android-ci.yml").readText()
        val installLines = workflow.lineSequence()
            .filter { line -> line.contains("sdkmanager") && line.contains("platforms;android") }
            .toList()

        assertEquals(2, installLines.size)
        assertTrue(installLines.all { line -> line.contains("platforms;android-37.2") })
        assertTrue(installLines.all { line -> line.contains("--channel=3") })
        assertFalse(workflow.contains("Debug available SDK platforms"))
    }
}
