package com.dawncourse.core.data.di

import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import com.dawncourse.core.data.local.dao.CourseDao
import com.dawncourse.core.data.local.dao.SyncSourceBindingDao
import com.dawncourse.core.data.local.dao.TimetableProfileDao
import com.dawncourse.core.data.repository.CourseRepositoryImpl
import com.dawncourse.core.data.repository.ActiveTimetableActionGateImpl
import com.dawncourse.core.data.repository.DiagnosticSampleRepositoryImpl
import com.dawncourse.core.data.repository.CredentialsRepositoryImpl
import com.dawncourse.core.data.repository.LocalBackupRepositoryImpl
import com.dawncourse.core.data.repository.LlmParseRepositoryImpl
import com.dawncourse.core.data.repository.ParseReportRepositoryImpl
import com.dawncourse.core.data.repository.SyncStateRepositoryImpl
import com.dawncourse.core.data.repository.TimetableSyncRepositoryImpl
import com.dawncourse.core.data.repository.TimetableProfileRepositoryImpl
import com.dawncourse.core.data.repository.ImportCommitRepositoryImpl
import com.dawncourse.core.data.repository.SyncSourceBindingRepositoryImpl
import com.dawncourse.core.data.repository.WebDavCredentialsRepositoryImpl
import com.dawncourse.core.data.repository.WebDavSyncRepositoryImpl
import com.dawncourse.core.data.repository.WidgetUpdateRepositoryImpl
import com.dawncourse.core.domain.repository.CalendarExportRepository
import com.dawncourse.core.domain.repository.ActiveTimetableActionGate
import com.dawncourse.core.data.repository.CalendarExportRepositoryImpl
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.DiagnosticSampleRepository
import com.dawncourse.core.domain.repository.CredentialsRepository
import com.dawncourse.core.domain.repository.LocalBackupRepository
import com.dawncourse.core.domain.repository.LlmParseRepository
import com.dawncourse.core.domain.repository.ParseReportRepository
import com.dawncourse.core.data.repository.SettingsRepositoryImpl
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.data.local.dao.SemesterDao
import com.dawncourse.core.data.repository.SemesterRepositoryImpl
import com.dawncourse.core.domain.repository.SemesterRepository
import com.dawncourse.core.domain.repository.SyncStateRepository
import com.dawncourse.core.domain.repository.TimetableSyncRepository
import com.dawncourse.core.domain.repository.TimetableProfileRepository
import com.dawncourse.core.domain.repository.ImportCommitRepository
import com.dawncourse.core.domain.repository.SyncSourceBindingRepository
import com.dawncourse.core.domain.repository.WebDavCredentialsRepository
import com.dawncourse.core.domain.repository.WebDavSyncRepository
import com.dawncourse.core.domain.repository.WidgetUpdateRepository
import com.dawncourse.core.domain.repository.OperationalDataGate
import dagger.Module
import dagger.Provides
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dawncourse.core.data.repository.settingsDataStore
import javax.inject.Singleton

/**
 * 数据库依赖注入模块 (Hilt Module)
 *
 * 负责提供数据库实例和 DAO 的依赖注入。
 * 安装在 [SingletonComponent] 中，意味着这些对象在整个应用生命周期内是单例的。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    /**
     * 提供 [AppDatabase] 实例
     *
     * @return Runtime 已完成 SQLCipher 首次连接与条件化同步校验的唯一 Room 实例
     */
    @Provides
    @Singleton
    fun provideAppDatabase(runtime: DatabaseStartupRuntime): AppDatabase =
        runtime.requireReadyDatabase()

    /** Feature 只依赖无 SQLCipher 细节的启动守卫。 */
    @Provides
    @Singleton
    fun provideOperationalDataGate(runtime: DatabaseStartupRuntime): OperationalDataGate = runtime
    
    /**
     * 提供 [CourseDao] 实例
     *
     * @param database 数据库实例
     * @return 从数据库中获取的 DAO 对象
     */
    @Provides
    @Singleton
    fun provideCourseDao(database: AppDatabase): CourseDao {
        return database.courseDao()
    }

    @Provides
    @Singleton
    fun provideSemesterDao(database: AppDatabase): SemesterDao {
        return database.semesterDao()
    }

    /** 设置与 active_profile_id 共用单一 DataStore，确保桥接具有原子性。 */
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideTimetableProfileDao(database: AppDatabase): TimetableProfileDao = database.timetableProfileDao()

    @Provides
    @Singleton
    fun provideSyncSourceBindingDao(database: AppDatabase): SyncSourceBindingDao = database.syncSourceBindingDao()
}

/**
 * 仓库依赖注入模块 (Hilt Module)
 *
 * 负责绑定 Domain 层的 Repository 接口与 Data 层的具体实现类。
 * 通过 @Binds 注解，Hilt 知道当请求某个接口时，应该提供哪个实现类的实例。
 * 这种方式避免了手动编写 provide 方法，生成的代码更少，性能更好。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /** 系统 Surface 的最终动作与 Profile 切换共用同一线性化门。 */
    @Binds
    @Singleton
    abstract fun bindActiveTimetableActionGate(
        impl: ActiveTimetableActionGateImpl,
    ): ActiveTimetableActionGate
    
    /**
     * 绑定 [CourseRepository] 接口到 [CourseRepositoryImpl] 实现
     *
     * 当需要注入 CourseRepository 时，Hilt 会自动提供 CourseRepositoryImpl 的实例。
     */
    @Binds
    @Singleton
    abstract fun bindCourseRepository(
        impl: CourseRepositoryImpl
    ): CourseRepository

    /** 绑定导入诊断副本的 no-backup 安全存储。 */
    @Binds
    @Singleton
    abstract fun bindDiagnosticSampleRepository(
        impl: DiagnosticSampleRepositoryImpl
    ): DiagnosticSampleRepository

    /**
     * 绑定 [SettingsRepository] 接口到 [SettingsRepositoryImpl] 实现
     * 负责应用设置数据的存取
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository

    /**
     * 绑定 [SemesterRepository] 接口到 [SemesterRepositoryImpl] 实现
     * 负责学期数据的存取
     */
    @Binds
    @Singleton
    abstract fun bindSemesterRepository(
        impl: SemesterRepositoryImpl
    ): SemesterRepository

    /**
     * 绑定 [CredentialsRepository] 接口到 [CredentialsRepositoryImpl] 实现
     * 负责用户凭证（如登录 Token）的安全存储
     */
    @Binds
    @Singleton
    abstract fun bindCredentialsRepository(
        impl: CredentialsRepositoryImpl
    ): CredentialsRepository

    /** 绑定多课表聚合仓库。 */
    @Binds
    @Singleton
    abstract fun bindTimetableProfileRepository(
        impl: TimetableProfileRepositoryImpl,
    ): TimetableProfileRepository

    /** 导入候选的 Profile 作用域事务提交入口。 */
    @Binds
    @Singleton
    abstract fun bindImportCommitRepository(
        impl: ImportCommitRepositoryImpl,
    ): ImportCommitRepository

    /** 将来源绑定门禁暴露给导入 Feature，避免反向依赖 core:data。 */
    @Binds
    @Singleton
    abstract fun bindSyncSourceBindingRepository(
        impl: SyncSourceBindingRepositoryImpl,
    ): SyncSourceBindingRepository

    /**
     * 绑定 [SyncStateRepository] 接口到 [SyncStateRepositoryImpl] 实现
     * 负责同步状态（如上次同步时间）的记录
     */
    @Binds
    @Singleton
    abstract fun bindSyncStateRepository(
        impl: SyncStateRepositoryImpl
    ): SyncStateRepository

    /**
     * 绑定 [TimetableSyncRepository] 接口到 [TimetableSyncRepositoryImpl] 实现
     * 负责课程表数据的网络同步逻辑
     */
    @Binds
    @Singleton
    abstract fun bindTimetableSyncRepository(
        impl: TimetableSyncRepositoryImpl
    ): TimetableSyncRepository

    @Binds
    @Singleton
    abstract fun bindWebDavCredentialsRepository(
        impl: WebDavCredentialsRepositoryImpl
    ): WebDavCredentialsRepository

    /**
     * 绑定 [WebDavSyncRepository] 接口到 [WebDavSyncRepositoryImpl] 实现
     * 负责 WebDAV 备份上传/下载与冲突处理
     */
    @Binds
    @Singleton
    abstract fun bindWebDavSyncRepository(
        impl: WebDavSyncRepositoryImpl
    ): WebDavSyncRepository

    @Binds
    @Singleton
    abstract fun bindLocalBackupRepository(
        impl: LocalBackupRepositoryImpl
    ): LocalBackupRepository

    /**
     * 绑定 [WidgetUpdateRepository] 接口到 [WidgetUpdateRepositoryImpl] 实现
     * 负责解耦组件刷新对 Context 的依赖
     */
    @Binds
    @Singleton
    abstract fun bindWidgetUpdateRepository(
        impl: WidgetUpdateRepositoryImpl
    ): WidgetUpdateRepository

    /**
     * 绑定 [ScriptSyncRepository] 接口到 [ScriptSyncRepositoryImpl] 实现
     * 负责云端脚本的拉取与缓存
     */
    @Binds
    @Singleton
    abstract fun bindScriptSyncRepository(
        impl: com.dawncourse.core.data.repository.ScriptSyncRepositoryImpl
    ): com.dawncourse.core.domain.repository.ScriptSyncRepository

    /**
     * 绑定 [LlmParseRepository] 接口到 [LlmParseRepositoryImpl] 实现
     * 负责 LLM 异步解析任务的提交与状态查询
     */
    @Binds
    @Singleton
    abstract fun bindLlmParseRepository(
        impl: LlmParseRepositoryImpl
    ): LlmParseRepository

    @Binds
    @Singleton
    abstract fun bindParseReportRepository(
        impl: ParseReportRepositoryImpl
    ): ParseReportRepository

    /**
     * 绑定 [CalendarExportRepository] 接口到 [CalendarExportRepositoryImpl] 实现
     * 负责将日历文件导出到指定 URI
     */
    @Binds
    @Singleton
    abstract fun bindCalendarExportRepository(
        impl: CalendarExportRepositoryImpl
    ): CalendarExportRepository
}
