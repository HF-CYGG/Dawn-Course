package com.dawncourse.feature.timetable.notification

import android.content.Context

/**
 * 覆盖升级兼容桥。
 *
 * 仅在首个 TriggerKey 发布周期启用，用于旧 MUTE 已执行、旧无 URI UNMUTE 尚未触发的设备。
 * 下一主版本需配合旧 Alarm 清理后删除本策略与 Manifest 的旧 action filter。
 */
object LegacyUnmuteUpgradePolicy {
    /** 发布迁移审计标记，禁止无期限保留。 */
    const val BRIDGE_RELEASE_MARKER = "trigger-key-v1-first-release"

    /**
     * 每次安装最多消费一次旧协议桥；同步块同时阻止重复旧 PendingIntent 并发恢复。
     * 只有真实恢复成功后才落持久标记，权限暂不可用时仍允许后续合法重试。
     */
    @Synchronized
    fun recoverOnce(
        context: Context,
        action: String?,
        dataUri: String?,
        hasProtectedSession: Boolean,
        recover: () -> Boolean,
    ): Boolean {
        if (!shouldRecover(action, dataUri, hasProtectedSession)) return false
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(CONSUMED_MARKER_KEY, null) == BRIDGE_RELEASE_MARKER) return false
        val recovered = recover()
        if (recovered) {
            preferences.edit().putString(CONSUMED_MARKER_KEY, BRIDGE_RELEASE_MARKER).commit()
        }
        return recovered
    }

    /** 只接受应用内 exported=false Receiver 收到的严格旧 UNMUTE。 */
    fun shouldRecover(
        action: String?,
        dataUri: String?,
        hasActiveOrPendingSession: Boolean = false
    ): Boolean = action == SilenceReceiver.ACTION_UNMUTE &&
        dataUri == null &&
        !hasActiveOrPendingSession

    private const val PREFERENCES_NAME = "dc_legacy_unmute_bridge"
    private const val CONSUMED_MARKER_KEY = "consumed_release"
}
