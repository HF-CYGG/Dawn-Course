package com.dawncourse.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * benchmark 数据种子的源码契约。
 *
 * Provider 仅编译进 benchmark 变体，普通 debug JVM 单测无法直接加载它；因此在不启动
 * 设备的前提下，固定其数据身份、SQLite sequence 重置与当前周学期锚点三个关键约束。
 */
class BenchmarkSeedProviderSourceContractTest {

    @Test
    fun `种子使用固定实体 ID 并在事务中重置 benchmark 数据库的自增序列`() {
        val source = benchmarkProviderSource()

        assertTrue(source.contains("id = BENCHMARK_SEMESTER_ID"))
        assertTrue(source.contains("val profileId = requireNotNull(database.timetableProfileDao().getFirstProfile())"))
        assertTrue(source.contains("profileId = profileId"))
        assertTrue(source.contains("id = BENCHMARK_COURSE_ID_START + index.toLong()"))
        assertTrue(source.contains("resetBenchmarkSequences(database)"))
        assertTrue(
            source.contains(
                "database.timetableProfileDao().updateActiveSemesterId(profileId, BENCHMARK_SEMESTER_ID)"
            )
        )
        assertTrue(source.contains("DELETE FROM sqlite_sequence WHERE name IN ('courses', 'semesters')"))
        assertTrue(
            source.indexOf("database.courseDao().deleteAllCourses()") <
                source.indexOf("resetBenchmarkSequences(database)")
        )
        assertTrue(
            source.indexOf("database.semesterDao().deleteAllSemesters()") <
                source.indexOf("resetBenchmarkSequences(database)")
        )
        assertTrue(
            source.indexOf("resetBenchmarkSequences(database)") <
                source.indexOf("database.semesterDao().insertSemester(benchmarkSemester(profileId))")
        )
        assertTrue(
            source.indexOf("database.semesterDao().insertSemester(benchmarkSemester(profileId))") <
                source.indexOf(
                    "database.timetableProfileDao().updateActiveSemesterId(profileId, " +
                        "BENCHMARK_SEMESTER_ID)"
                )
        )
    }

    @Test
    fun `种子学期在每次播种时锚定本地当前周的周一而非固定日历日期`() {
        val source = benchmarkProviderSource()

        assertTrue(source.contains("LocalDate.now(ZoneId.systemDefault())"))
        assertTrue(source.contains("TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)"))
        assertFalse(source.contains("LocalDate.of(2026, 8, 24)"))
    }

    /** 返回仅属于 benchmark 变体的数据种子源码。 */
    private fun benchmarkProviderSource(): String = File(
        "src/benchmark/java/com/dawncourse/app/benchmark/BenchmarkSeedProvider.kt"
    ).also { file ->
        assertTrue("缺少 benchmark 数据种子：${file.absolutePath}", file.isFile)
    }.readText()
}
