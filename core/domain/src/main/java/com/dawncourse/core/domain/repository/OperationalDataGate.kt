package com.dawncourse.core.domain.repository

/**
 * 业务数据在当前进程中的可访问状态。
 *
 * Feature 只需要知道是否可以读取 Operational Model，不应感知 SQLCipher、Keystore
 * 或恢复文件的具体实现。
 */
enum class OperationalDataReadiness {
    /** 数据库仍在检查、迁移或打开。 */
    STARTING,

    /** 数据库已完成加密打开与完整性校验。 */
    READY,

    /** 数据库不可安全访问，必须等待前台恢复流程。 */
    RECOVERY_REQUIRED
}

/** 后台入口在触碰 Repository 前使用的最小启动守卫。 */
interface OperationalDataGate {
    /** 返回当前瞬时状态；本方法不阻塞线程，也不会触发数据库创建。 */
    fun readiness(): OperationalDataReadiness
}
