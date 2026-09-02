package com.dawncourse.feature.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 设置页展示的是用户期望与当前 DND 能力，而不是用权限反向覆盖偏好。 */
enum class AutoMuteAvailability {
    DISABLED,
    ENABLED_DND_AVAILABLE,

    /**
     * 平台本身不提供应用级勿扰（低于 Android 15），用户无论如何操作都改变不了。
     * 必须与"权限未授予"区分开，否则文案会把永久的平台事实说成可修复的临时故障。
     */
    ENABLED_DND_UNSUPPORTED_VIBRATE,

    /** 平台支持但尚未授予勿扰权限；这是用户可以自己修复的状态，必须给出入口。 */
    ENABLED_DND_PERMISSION_REQUIRED_VIBRATE,
}

/** 区分平台支持与授权真相，避免旧 Android 诱导申请不会使用的 DND 权限。 */
data class AutoMuteDndCapability(
    val isSupported: Boolean,
    val hasPolicyAccess: Boolean,
) {
    val isAvailable: Boolean
        get() = isSupported && hasPolicyAccess

    val shouldRequestPolicyAccess: Boolean
        get() = isSupported && !hasPolicyAccess
}

object AutoMuteAvailabilityPolicy {
    fun resolve(
        desiredEnabled: Boolean,
        capability: AutoMuteDndCapability,
    ): AutoMuteAvailability = when {
        !desiredEnabled -> AutoMuteAvailability.DISABLED
        capability.isAvailable -> AutoMuteAvailability.ENABLED_DND_AVAILABLE
        capability.shouldRequestPolicyAccess ->
            AutoMuteAvailability.ENABLED_DND_PERMISSION_REQUIRED_VIBRATE
        else -> AutoMuteAvailability.ENABLED_DND_UNSUPPORTED_VIBRATE
    }
}

/** ON_RESUME 时重新读取；不缓存系统设置页面返回前的旧权限。 */
@Singleton
class AutoMuteDndAvailabilityReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun readCapability(): AutoMuteDndCapability {
        val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        if (!isSupported) return AutoMuteDndCapability(false, false)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return AutoMuteDndCapability(
            isSupported = true,
            hasPolicyAccess = manager.isNotificationPolicyAccessGranted,
        )
    }
}
