package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁定 GitHub Actions 使用 SDK Manager 实际发布的 Android 17 平台包名。 */
class AndroidCiSdkPackageContractTest {

    @Test
    fun `Android CI 每个构建任务都安装 android 37 point 2`() {
        val workflow = File("../.github/workflows/android-ci.yml").readText()
        val installLines = workflow.lineSequence()
            .filter { line -> line.contains("sdkmanager") && line.contains("platforms;android") }
            .toList()

        // 并行 job（build / test / lint / release-smoke）各自安装一次；数量随 job 拆分变化，
        // 这里只锁定“每个安装点都用同一个正确的平台包与渠道”，不锁定 job 数。
        assertEquals(
            workflow.lineSequence().count { line -> line.contains("runs-on: ubuntu-latest") },
            installLines.size
        )
        assertTrue(installLines.all { line -> line.contains("platforms;android-37.2") })
        assertTrue(installLines.all { line -> line.contains("--channel=3") })
        assertFalse(workflow.contains("Debug available SDK platforms"))
    }

    @Test
    fun `CodeQL 的抽取构建必须禁用 Gradle 构建缓存`() {
        val workflow = File("../.github/workflows/codeql.yml").readText()

        // 命中构建缓存时编译任务不会真正执行，CodeQL manual trace 抽取不到源码，
        // database finalize 会报 "no source code seen"。
        assertTrue(workflow.contains("--no-build-cache"))
        assertFalse(workflow.contains("--build-cache\n"))
    }
}
