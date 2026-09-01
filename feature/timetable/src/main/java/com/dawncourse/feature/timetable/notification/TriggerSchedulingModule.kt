package com.dawncourse.feature.timetable.notification

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 系统触发器 Android Adapter 的 Hilt 绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TriggerSchedulingModule {
    /** 绑定应用所有静音会话持久化。 */
    @Binds
    @Singleton
    abstract fun bindMuteSessionPersistence(
        implementation: AppMuteSessionStore
    ): MuteSessionPersistence

    /** 绑定可枚举 SharedPreferences 注册表。 */
    @Binds
    @Singleton
    abstract fun bindScheduledTriggerRegistry(
        implementation: SharedPreferencesScheduledTriggerRegistry
    ): ScheduledTriggerRegistry

    /** 绑定 AlarmManager 网关。 */
    @Binds
    @Singleton
    abstract fun bindTriggerAlarmGateway(
        implementation: AndroidTriggerAlarmGateway
    ): TriggerAlarmGateway

    /** 绑定按 Key 持久执行的静音恢复 Worker 调度器。 */
    @Binds
    @Singleton
    abstract fun bindMuteRecoveryWorkScheduler(
        implementation: WorkManagerMuteRecoveryScheduler
    ): MuteRecoveryWorkScheduler

    /** 绑定启动窗口内错过数据库就绪时的一次性触发补投调度器。 */
    @Binds
    @Singleton
    abstract fun bindTriggerReadinessRetryScheduler(
        implementation: WorkManagerTriggerReadinessRetryScheduler
    ): TriggerReadinessRetryScheduler

    /** 绑定独立低噪声恢复警示。 */
    @Binds
    @Singleton
    abstract fun bindMuteRecoveryAttention(
        implementation: MuteRecoveryNotificationHelper
    ): MuteRecoveryAttention
}
