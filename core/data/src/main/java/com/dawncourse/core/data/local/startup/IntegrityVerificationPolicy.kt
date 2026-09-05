package com.dawncourse.core.data.local.startup

/** 完整性验证相对于 [DatabaseRuntimeState.Ready] 的执行时机。 */
enum class IntegrityVerificationMode {
    /** 双 PRAGMA 全部成功后才允许发布 Ready。 */
    SYNCHRONOUS_BEFORE_READY,

    /** Room 首次连接完成后先发布 Ready，再由后台承担双 PRAGMA。 */
    BACKGROUND_AFTER_READY,
}

/** 纯策略输入；不包含数据库路径、口令或底层 SQL 结果。 */
data class IntegrityVerificationPolicyInput(
    /** 本次启动是否恢复了未完成的明文加密迁移 journal。 */
    val recoveredIncompleteMigrationThisRun: Boolean,
    /** 启动前是否已经存在任一持久恢复责任。 */
    val recoveryResponsibilityMarkerPresent: Boolean,
    /** 上次进程是否在数据库启动责任尚未收口时结束。 */
    val previousDatabaseStartupIncomplete: Boolean,
    /** 本次是否刚完成明文数据库加密换入。 */
    val migratedPlaintextThisRun: Boolean,
    /** 本次是否完成了 SQLCipher rekey 或密钥模式切换（v1 派生口令 -> v2 raw key）。 */
    val rekeyOrKeyModeChangedThisRun: Boolean,
    /** 持久状态是否损坏或无法可靠读取。 */
    val persistentStateUnreadable: Boolean,
    /** 当前墙上时钟毫秒。 */
    val nowEpochMillis: Long,
    /** 上次双完整性校验成功的墙上时钟毫秒；首次安装为 null。 */
    val lastSuccessfulVerificationEpochMillis: Long?,
)

/** 决定冷启动是否必须同步承担数据库双完整性扫描。 */
object IntegrityVerificationPolicy {
    /** 七天整是同步边界，不能延后到下一毫秒。 */
    const val SYNCHRONOUS_INTERVAL_MILLIS: Long = 7L * 24L * 60L * 60L * 1_000L

    /** 任一恢复、迁移、密钥或时间证据异常都保守选择同步扫描。 */
    fun decide(input: IntegrityVerificationPolicyInput): IntegrityVerificationMode {
        if (
            input.recoveredIncompleteMigrationThisRun ||
            input.recoveryResponsibilityMarkerPresent ||
            input.previousDatabaseStartupIncomplete ||
            input.migratedPlaintextThisRun ||
            input.rekeyOrKeyModeChangedThisRun ||
            input.persistentStateUnreadable
        ) {
            return IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY
        }
        val lastSuccess = input.lastSuccessfulVerificationEpochMillis
        if (input.nowEpochMillis <= 0L || lastSuccess == null || lastSuccess <= 0L) {
            return IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY
        }
        if (input.nowEpochMillis < lastSuccess) {
            return IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY
        }
        return if (input.nowEpochMillis - lastSuccess >= SYNCHRONOUS_INTERVAL_MILLIS) {
            IntegrityVerificationMode.SYNCHRONOUS_BEFORE_READY
        } else {
            IntegrityVerificationMode.BACKGROUND_AFTER_READY
        }
    }
}
