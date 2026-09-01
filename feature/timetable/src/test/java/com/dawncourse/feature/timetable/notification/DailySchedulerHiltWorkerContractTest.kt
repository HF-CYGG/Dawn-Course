package com.dawncourse.feature.timetable.notification

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DailySchedulerWorker 的 Hilt 构造注入契约测试。 */
class DailySchedulerHiltWorkerContractTest {

    @Test
    fun `每日调度 Worker 先守卫数据库再延迟解析 Repository`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/timetable/notification/DailySchedulerWorker.kt"
        ).also { file -> assertTrue("缺少 Worker 源码：${file.absolutePath}", file.isFile) }.readText()

        assertTrue(source.contains("@HiltWorker"))
        assertTrue(source.contains("@AssistedInject constructor"))
        assertTrue(source.contains("@Assisted appContext: Context"))
        assertTrue(source.contains("private val operationalDataGate: OperationalDataGate"))
        assertTrue(source.contains("private val courseRepository: Lazy<CourseRepository>"))
        assertTrue(source.contains("private val settingsRepository: Lazy<SettingsRepository>"))
        assertTrue(source.contains("private val timetableProfileRepository: Lazy<TimetableProfileRepository>"))
        assertTrue(source.contains("private val calculateWeekUseCase: CalculateWeekUseCase"))
        assertFalse(source.contains("EntryPointAccessors"))
        assertFalse(source.contains("WorkerEntryPoint"))
    }
}
