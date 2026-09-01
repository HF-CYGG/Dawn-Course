package com.dawncourse.app.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Trace
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.dawncourse.app.DawnApp
import com.dawncourse.app.DawnAppInitializationGate
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.startup.DatabaseRuntimeState
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import com.dawncourse.core.data.local.entity.CourseEntity
import com.dawncourse.core.data.local.entity.SemesterEntity
import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.SectionTime
import com.dawncourse.core.domain.model.AppThemeMode
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.feature.timetable.notification.ReminderScheduler
import com.dawncourse.feature.widget.MidnightUpdateReceiver
import com.dawncourse.feature.widget.WidgetTimelineBuilder
import com.dawncourse.feature.widget.worker.WidgetSyncManager
import com.dawncourse.app.sync.WebDavAutoSyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 仅编译进 benchmark / Baseline Profile 专用构建类型的数据种子 Provider。
 *
 * 它不会出现在 debug、release 或生产 manifest，所有写入仅针对基准测试安装的 app 数据目录。
 */
class BenchmarkSeedProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        awaitDawnAppInitialization()
        return when (method) {
            METHOD_SEED_COURSES -> seedCourses()
            METHOD_WIDGET_DATA_BUILD -> buildWidgetTimeline()
            else -> throw IllegalArgumentException("Unsupported benchmark method: $method")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor = throw UnsupportedOperationException("Benchmark provider only supports call()")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Benchmark provider only supports call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("Benchmark provider only supports call()")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = throw UnsupportedOperationException("Benchmark provider only supports call()")

    private fun seedCourses(): Bundle {
        val appContext = requireNotNull(context).applicationContext
        resetBenchmarkState(appContext)
        val database = openDatabase()
        return run {
            runBlocking {
                database.withTransaction {
                    database.courseDao().deleteAllCourses()
                    database.semesterDao().deleteAllSemesters()
                    resetBenchmarkSequences(database)
                    database.semesterDao().insertSemester(benchmarkSemester())
                    database.courseDao().insertCourses(benchmarkCourses())
                }
            }
            runBlocking {
                EntryPointAccessors.fromApplication(
                    appContext,
                    BenchmarkEntryPoint::class.java
                ).settingsRepository().restoreAllSettingsAndSelection(
                    settings = BENCHMARK_SETTINGS,
                    selectedSemesterId = BENCHMARK_SEMESTER_ID
                )
            }
            Bundle().apply { putInt(KEY_COURSE_COUNT, COURSE_COUNT) }
        }
    }

    /** 仅触发生产 Widget 的 Hilt -> Repository -> Room 数据构建，不复制其筛选算法。 */
    private fun buildWidgetTimeline(): Bundle {
        // BaselineProfileRule 会在 collect 前重启目标进程；Application.onCreate 完成时，
        // SQLCipher 启动仍可能处于异步 Starting。先复用 seed 路径的 Ready 门禁，
        // 再从 Hilt 获取依赖 AppDatabase 的 WidgetTimelineBuilder。
        openDatabase()
        val sourceCourseCount = runBlocking {
            Trace.beginSection(TRACE_WIDGET_DATA_BUILD)
            try {
                EntryPointAccessors.fromApplication(
                    requireNotNull(context).applicationContext,
                    BenchmarkEntryPoint::class.java
                ).widgetTimelineBuilder().build().sourceCourseCount
            } finally {
                Trace.endSection()
            }
        }
        return Bundle().apply { putInt(KEY_COURSE_COUNT, sourceCourseCount) }
    }

    /**
     * 重置仅安装在 benchmark 变体中的隔离应用状态。
     *
     * Provider 不会合入 debug/release manifest；因此这里不会访问用户正式安装包的数据。
     */
    private fun resetBenchmarkState(appContext: android.content.Context) {
        ReminderScheduler.cancelWork(appContext)
        WebDavAutoSyncScheduler.cancel(appContext)
        WidgetSyncManager.cancelUpdate(appContext)
        WidgetSyncManager.cancelNextCourseUpdate(appContext)
        MidnightUpdateReceiver.cancelNextMidnightUpdate(appContext)
        WorkManager.getInstance(appContext).cancelAllWork().result.get()
        File(appContext.filesDir, BLURRED_WALLPAPER_FILE_NAME).delete()
    }

    /**
     * Provider 安装可能早于 [android.app.Application.onCreate]；此时 WorkManager 读取
     * [com.dawncourse.app.DawnApp.workerFactory] 会失败，且不能与 Widget 初始化器并发重置。
     * 只等待一个有限窗口；失败时终止本次 seed，避免测量到未完整重置的状态。
     */
    private fun awaitDawnAppInitialization() {
        when (val result = DawnApp.awaitBenchmarkInitialization(BENCHMARK_APP_READY_TIMEOUT_MS)) {
            DawnAppInitializationGate.AwaitResult.Ready -> Unit
            DawnAppInitializationGate.AwaitResult.TimedOut -> {
                throw IllegalStateException(
                    "Benchmark seed aborted: DawnApp initialization did not finish within " +
                        "${BENCHMARK_APP_READY_TIMEOUT_MS}ms."
                )
            }
            is DawnAppInitializationGate.AwaitResult.Failed -> {
                throw IllegalStateException(
                    "Benchmark seed aborted: DawnApp initialization failed.",
                    result.cause
                )
            }
        }
    }

    private fun openDatabase(): AppDatabase {
        val appContext = requireNotNull(context).applicationContext
        val runtime = EntryPointAccessors.fromApplication(
            appContext,
            BenchmarkEntryPoint::class.java
        ).databaseStartupRuntime()
        runBlocking {
            withTimeout(BENCHMARK_DATABASE_READY_TIMEOUT_MS) {
                runtime.state.filter { state -> state !is DatabaseRuntimeState.Starting }.first()
            }
        }
        check(runtime.state.value is DatabaseRuntimeState.Ready) {
            "Benchmark seed aborted: encrypted database requires recovery."
        }
        return runtime.requireReadyDatabase()
    }

    /**
     * 重置 benchmark 专用库中两张 AUTOINCREMENT 表的高水位。
     *
     * 仅删除行不会重置 SQLite 的 sqlite_sequence；固定主键与固定高水位必须同时成立，
     * 否则连续 100 次 Macrobenchmark 会出现不同的后续插入 ID 和数据库页布局。
     */
    private fun resetBenchmarkSequences(database: AppDatabase) {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM sqlite_sequence WHERE name IN ('courses', 'semesters')"
        )
    }

    private fun benchmarkSemester(): SemesterEntity {
        val startDate = benchmarkSemesterStartDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return SemesterEntity(
            id = BENCHMARK_SEMESTER_ID,
            name = "Benchmark Semester",
            startDate = startDate,
            weekCount = BENCHMARK_WEEK_COUNT,
        )
    }

    /**
     * 以调用时所在本地周的周一作为第一周开始，保证长期运行的 benchmark 不会落入假期。
     * 同一日期内的所有重复 seed 都得到相同值；跨周后再次 seed 仍始终处于第 1 周。
     */
    private fun benchmarkSemesterStartDate(): LocalDate = LocalDate.now(ZoneId.systemDefault())
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 构造覆盖 52 周、140 条课程的固定样本，用于课表滚动与 Room 查询压力。 */
    private fun benchmarkCourses(): List<CourseEntity> {
        return (0 until COURSE_COUNT).map { index ->
            CourseEntity(
                id = BENCHMARK_COURSE_ID_START + index.toLong(),
                semesterId = BENCHMARK_SEMESTER_ID,
                name = "Benchmark Course ${index + 1}",
                teacher = "Teacher ${(index % 10) + 1}",
                location = "Room ${(index % 30) + 101}",
                dayOfWeek = (index % 7) + 1,
                startSection = (index % 12) + 1,
                duration = if (index % 3 == 0) 2 else 1,
                startWeek = 1,
                endWeek = BENCHMARK_WEEK_COUNT,
                weekType = Course.WEEK_TYPE_ALL,
                color = BENCHMARK_COLORS[index % BENCHMARK_COLORS.size]
            )
        }
    }

    private companion object {
        const val METHOD_SEED_COURSES = "seed_courses"
        const val METHOD_WIDGET_DATA_BUILD = "widget_data_build"
        const val KEY_COURSE_COUNT = "course_count"
        const val BENCHMARK_SEMESTER_ID = 1L
        const val BENCHMARK_COURSE_ID_START = 1L
        const val COURSE_COUNT = 140
        const val BENCHMARK_WEEK_COUNT = 52
        const val TRACE_WIDGET_DATA_BUILD = "DawnCourseBenchmark#widgetDataBuild"
        const val BLURRED_WALLPAPER_FILE_NAME = "blurred_wallpaper.jpg"
        const val BENCHMARK_APP_READY_TIMEOUT_MS = 10_000L
        const val BENCHMARK_DATABASE_READY_TIMEOUT_MS = 30_000L
        val BENCHMARK_COLORS = listOf("#FFB74D", "#64B5F6", "#81C784", "#BA68C8")
        val BENCHMARK_SETTINGS = AppSettings(
            dynamicColor = false,
            themeMode = AppThemeMode.LIGHT,
            sectionTimes = listOf(
                SectionTime("08:00", "08:45"),
                SectionTime("08:55", "09:40"),
                SectionTime("10:00", "10:45"),
                SectionTime("10:55", "11:40"),
                SectionTime("14:00", "14:45"),
                SectionTime("14:55", "15:40"),
                SectionTime("16:00", "16:45"),
                SectionTime("16:55", "17:40"),
                SectionTime("19:00", "19:45"),
                SectionTime("19:55", "20:40"),
                SectionTime("20:50", "21:35"),
                SectionTime("21:45", "22:30")
            )
        )
    }

    /** 仅测试变体获取公开生产入口，不复制 DataStore 或 Widget 私有协议。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BenchmarkEntryPoint {
        fun databaseStartupRuntime(): DatabaseStartupRuntime
        fun settingsRepository(): SettingsRepository
        fun widgetTimelineBuilder(): WidgetTimelineBuilder
    }
}
