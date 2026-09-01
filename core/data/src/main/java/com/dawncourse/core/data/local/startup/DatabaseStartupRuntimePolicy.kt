package com.dawncourse.core.data.local.startup

import com.dawncourse.core.domain.repository.OperationalDataReadiness

/** 后台 Worker 面对数据库启动状态时的确定性动作。 */
enum class BackgroundDatabaseAction {
    /** 数据已就绪，可以解析 Repository。 */
    RUN,

    /** 启动尚未结束，交给调度器稍后重试。 */
    RETRY_LATER,

    /** 已进入恢复模式，本次工作安全结束，避免形成无限重试。 */
    STOP_SAFELY
}

/** Receiver 是否允许解析数据库依赖。 */
enum class ReceiverDatabaseAction {
    /** 可安全解析并使用 Repository。 */
    RUN_WITH_DATABASE,

    /** UNMUTE 等补偿路径不依赖数据库，必须继续执行。 */
    RUN_WITHOUT_DATABASE,

    /** 当前入口必须在解析 Repository 前停止。 */
    STOP_SAFELY
}

/** 将启动状态映射为 Worker/Receiver 行为的纯 Kotlin 策略。 */
object DatabaseStartupRuntimePolicy {
    /** Worker 在 Starting 时重试，在 RecoveryRequired 时停止制造后台噪音。 */
    fun backgroundAction(readiness: OperationalDataReadiness): BackgroundDatabaseAction =
        when (readiness) {
            OperationalDataReadiness.STARTING -> BackgroundDatabaseAction.RETRY_LATER
            OperationalDataReadiness.READY -> BackgroundDatabaseAction.RUN
            OperationalDataReadiness.RECOVERY_REQUIRED -> BackgroundDatabaseAction.STOP_SAFELY
        }

    /** 非数据库补偿路径不受启动状态影响；其它 Receiver 只在 Ready 时运行。 */
    fun receiverAction(
        readiness: OperationalDataReadiness,
        requiresOperationalDatabase: Boolean
    ): ReceiverDatabaseAction {
        if (!requiresOperationalDatabase) return ReceiverDatabaseAction.RUN_WITHOUT_DATABASE
        return if (readiness == OperationalDataReadiness.READY) {
            ReceiverDatabaseAction.RUN_WITH_DATABASE
        } else {
            ReceiverDatabaseAction.STOP_SAFELY
        }
    }
}
