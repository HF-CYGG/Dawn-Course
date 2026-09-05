package com.dawncourse.feature.timetable.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.dawncourse.core.domain.model.TriggerKey
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Android 铃声 Adapter；恢复责任与并发策略由 [MuteSessionCoordinator] 统一处理。 */
@Singleton
class SilenceHelper @Inject constructor(
    private val coordinator: MuteSessionCoordinator
) {
    /** 为课程建立应用所有的静音会话。 */
    fun muteForSession(
        context: Context,
        unmuteKey: TriggerKey,
        recoveryAt: Instant? = null
    ): Boolean = coordinator.mute(
        unmuteKey = unmuteKey,
        ringer = AndroidRingerController(context.applicationContext),
        recoveryAt = recoveryAt
    )

    /** 恢复应用所有会话，失败时返回有限重放决策。 */
    fun unmuteOwnedSession(context: Context, unmuteKey: TriggerKey): MuteRecoveryOutcome =
        coordinator.unmute(unmuteKey, AndroidRingerController(context.applicationContext))

    /** 一个发布周期的旧版无 URI UNMUTE 恢复桥。 */
    fun recoverLegacyUnmute(context: Context): Boolean =
        coordinator.recoverLegacyUnmute(AndroidRingerController(context.applicationContext))

    private class AndroidRingerController(context: Context) : RingerController {
        private val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val appOwnedDndController = AppOwnedDndController(
            AndroidAppDndPlatform(notificationManager),
        )

        override val hasPolicyAccess: Boolean
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.isNotificationPolicyAccessGranted
            } else {
                true
            }

        override val state: RingerState
            get() = when (audioManager.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> RingerState.NORMAL
                AudioManager.RINGER_MODE_VIBRATE -> RingerState.VIBRATE
                else -> RingerState.SILENT
            }

        override val supportsAppOwnedDnd: Boolean
            get() = appOwnedDndController.isSupported

        override fun activateAppOwnedDnd(): Boolean = appOwnedDndController.activate()

        override fun deactivateAppOwnedDnd(): Boolean = appOwnedDndController.deactivate()

        override fun setVibrate() {
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        }

        override fun setNormal() {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
    }
}
