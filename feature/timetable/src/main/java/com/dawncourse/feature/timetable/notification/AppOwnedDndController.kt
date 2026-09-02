package com.dawncourse.feature.timetable.notification

import android.app.NotificationManager
import android.os.Build

/** 只暴露应用隐式 AutomaticZenRule 所需的两个过滤状态。 */
internal enum class AppDndFilter { PRIORITY, ALL }

/** Android API 的可替换边界，JVM 测试不依赖 framework stub 行为。 */
internal interface AppDndPlatform {
    val sdkInt: Int
    val hasPolicyAccess: Boolean
    fun setInterruptionFilter(filter: AppDndFilter)
}

/**
 * targetSdk 35+ 下 setInterruptionFilter 只激活/撤销调用方自己的隐式 AutomaticZenRule。
 * API 34 及以下拒绝调用，避免旧平台把请求应用到全局 DND。
 */
internal class AppOwnedDndController(
    private val platform: AppDndPlatform,
) {
    val isSupported: Boolean
        get() = platform.sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    fun activate(): Boolean = applyIfAllowed(AppDndFilter.PRIORITY)

    fun deactivate(): Boolean = applyIfAllowed(AppDndFilter.ALL)

    private fun applyIfAllowed(filter: AppDndFilter): Boolean {
        if (!isSupported || !platform.hasPolicyAccess) return false
        platform.setInterruptionFilter(filter)
        return true
    }
}

/** framework Adapter；不会枚举、更新或删除用户及其他应用的规则。 */
internal class AndroidAppDndPlatform(
    private val notificationManager: NotificationManager,
) : AppDndPlatform {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override val hasPolicyAccess: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            false
        }

    override fun setInterruptionFilter(filter: AppDndFilter) {
        val frameworkFilter = when (filter) {
            AppDndFilter.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            AppDndFilter.ALL -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
        notificationManager.setInterruptionFilter(frameworkFilter)
    }
}
