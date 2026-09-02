package com.dawncourse.feature.widget.worker

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WidgetUpdateWorker 的 Hilt 构造注入契约测试。 */
class WidgetUpdateHiltWorkerContractTest {

    @Test
    fun `Widget 更新 Worker 使用 HiltWorker 构造注入`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/widget/worker/WidgetUpdateWorker.kt"
        ).also { file -> assertTrue("缺少 Worker 源码：${file.absolutePath}", file.isFile) }.readText()

        assertTrue(source.contains("@HiltWorker"))
        assertTrue(source.contains("@AssistedInject constructor"))
        assertTrue(source.contains("@Assisted appContext: Context"))
        assertTrue(source.contains("private val operationalDataGate: OperationalDataGate"))
        assertFalse(source.contains("EntryPointAccessors"))
        assertFalse(source.contains("WorkerEntryPoint"))
    }
}
