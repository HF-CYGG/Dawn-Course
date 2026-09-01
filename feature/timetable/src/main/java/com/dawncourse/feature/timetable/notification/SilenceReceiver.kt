package com.dawncourse.feature.timetable.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dawncourse.core.domain.model.TriggerKey
import com.dawncourse.core.domain.model.TriggerKind
import com.dawncourse.core.domain.repository.CourseRepository
import com.dawncourse.core.domain.repository.ActiveTimetableActionGate
import com.dawncourse.core.domain.repository.SettingsRepository
import com.dawncourse.core.domain.repository.OperationalDataGate
import com.dawncourse.core.domain.repository.OperationalDataReadiness
import com.dawncourse.core.domain.usecase.CalculateWeekUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 仅处理有效新 TriggerKey URI 的应用静音会话广播。 */
class SilenceReceiver : BroadcastReceiver() {
    companion object {
        /** 课程开始静音 action。 */
        const val ACTION_MUTE = "com.dawncourse.action.MUTE"
        /** 课程结束安全恢复 action。 */
        const val ACTION_UNMUTE = "com.dawncourse.action.UNMUTE"
        private const val TAG = "SilenceReceiver"
    }

    /** MUTE 二次校验所需领域依赖。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        /** MUTE 在解析数据库 Repository 前检查；UNMUTE 补偿路径不依赖此状态。 */
        fun operationalDataGate(): OperationalDataGate
        /** 设置仓库。 */
        fun settingsRepository(): SettingsRepository
        /** 课程仓库。 */
        fun courseRepository(): CourseRepository
        /** 与 Profile 切换共用的最终动作线性化门。 */
        fun activeTimetableActionGate(): ActiveTimetableActionGate
        /** 周次计算用例。 */
        fun calculateWeekUseCase(): CalculateWeekUseCase
        /** 进程内唯一静音协调入口，内部持有同一 Singleton Store。 */
        fun silenceHelper(): SilenceHelper
        /** 静音责任持久状态，用于阻止 legacy 恢复覆盖新会话。 */
        fun appMuteSessionStore(): AppMuteSessionStore
        /** 按完整 Key 持久执行的专用恢复 Worker 调度器。 */
        fun muteRecoveryWorkScheduler(): MuteRecoveryWorkScheduler
        /** Receiver 恢复结果的异步入队与失败回滚编排器。 */
        fun muteRecoveryOutcomeDispatcher(): MuteRecoveryOutcomeDispatcher
    }

    /** 旧 MUTE/Reminder 仍拒绝；仅首个发布周期桥接严格旧 UNMUTE。 */
    override fun onReceive(context: Context, intent: Intent) {
        val legacyCandidate = LegacyUnmuteUpgradePolicy.shouldRecover(intent.action, intent.dataString)
        val key = if (legacyCandidate) null else TriggerIntentPolicy.parse(intent.action, intent.dataString)
        if (!legacyCandidate && (key == null || key.profileId == TriggerKey.LEGACY_PROFILE_ID)) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    ReceiverEntryPoint::class.java
                )
                if (legacyCandidate) {
                    LegacyUnmuteUpgradePolicy.recoverOnce(
                        context = context.applicationContext,
                        action = intent.action,
                        dataUri = intent.dataString,
                        // EXHAUSTED 仍是应用持有的恢复责任，旧桥不得绕过它擅自恢复铃声。
                        hasProtectedSession = entryPoint.appMuteSessionStore().protectedKeys().isNotEmpty(),
                    ) {
                        entryPoint.silenceHelper().recoverLegacyUnmute(context)
                    }
                } else if (key != null) {
                    when (key.kind) {
                        TriggerKind.MUTE -> {
                            if (entryPoint.operationalDataGate().readiness() == OperationalDataReadiness.READY) {
                                muteIfStillValid(context, key, entryPoint)
                            }
                        }
                        TriggerKind.UNMUTE -> recoverOwnedSession(
                            context = context,
                            key = key,
                            helper = entryPoint.silenceHelper(),
                            dispatcher = entryPoint.muteRecoveryOutcomeDispatcher()
                        )
                        TriggerKind.REMINDER -> Unit
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Log.e(TAG, "静音广播处理失败: ${failure.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** 恢复失败时只安排携带完整 Key 的专用持久 Worker。 */
    private suspend fun recoverOwnedSession(
        context: Context,
        key: TriggerKey,
        helper: SilenceHelper,
        dispatcher: MuteRecoveryOutcomeDispatcher
    ) {
        dispatcher.dispatch(key, helper.unmuteOwnedSession(context, key))
    }

    /** MUTE 必须重新校验开关、当前课程与实际进行时间窗口。 */
    private suspend fun muteIfStillValid(
        context: Context,
        key: TriggerKey,
        entryPoint: ReceiverEntryPoint
    ) {
        val candidate = entryPoint.courseRepository().getCourseById(key.courseId) ?: return
        val recovery = entryPoint.activeTimetableActionGate().executeIfActive(
            profileId = key.profileId,
            semesterId = candidate.semesterId,
        ) { activeContext ->
            val semester = activeContext.semester ?: return@executeIfActive null
            val course = entryPoint.courseRepository().getCourseById(key.courseId)
                ?.takeIf { it.semesterId == semester.id } ?: return@executeIfActive null
            val settings = entryPoint.settingsRepository().settings.first()
            if (!settings.enableAutoMute) return@executeIfActive null
            val zoneId = ZoneId.systemDefault()
            val now = Instant.now()
            val occurrenceMillis = key.occurrenceDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val currentWeek = entryPoint.calculateWeekUseCase().invoke(semester.startDate, occurrenceMillis)
            if (currentWeek !in 1..semester.weekCount) return@executeIfActive null
            if (!TriggerOccurrencePolicy.isInCourseWindow(
                    course = course,
                    occurrenceDate = key.occurrenceDate,
                    currentWeek = currentWeek,
                    now = now,
                    zoneId = zoneId,
                    sectionTimes = settings.sectionTimes
                )
            ) return@executeIfActive null
            val recoveryAt = TriggerOccurrencePolicy.courseEndAt(
                course = course,
                occurrenceDate = key.occurrenceDate,
                zoneId = zoneId,
                sectionTimes = settings.sectionTimes
            ) ?: return@executeIfActive null
            val unmuteKey = key.copy(kind = TriggerKind.UNMUTE)
            val ownsResponsibility = entryPoint.silenceHelper().muteForSession(
                context = context,
                unmuteKey = unmuteKey,
                recoveryAt = recoveryAt
            )
            if (ownsResponsibility) PendingRecovery(unmuteKey, recoveryAt) else null
        }
        if (recovery != null) {
            // Store 已先持久化；即使进程在 Operation 完成前死亡，启动 reconcile 仍可按 Key 修补。
            val enqueued = entryPoint.muteRecoveryWorkScheduler().enqueueAt(recovery.key, recovery.recoveryAt)
            if (!enqueued) {
                // 不输出 Key/课程数据；ACTIVE 责任及 recoveryAt 已持久化，启动 reconcile 会再次修补。
                Log.w(TAG, "静音恢复保底任务入队失败，将由启动对账修补")
            }
        }
    }

    /** gate 内持久化责任后，gate 外只负责安排可补偿的 Worker。 */
    private data class PendingRecovery(
        val key: TriggerKey,
        val recoveryAt: Instant,
    )

}
