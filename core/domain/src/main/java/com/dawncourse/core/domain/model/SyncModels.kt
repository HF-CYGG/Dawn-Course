package com.dawncourse.core.domain.model

/**
 * 同步提供者类型
 *
 * 标识当前一键更新所使用的数据来源类型，便于在数据层进行差异化处理。
 * 目前实现：WAKEUP（WakeUp 课程表口令）
 * 未来可扩展：QIDI（起迪教务）、ZF（正方教务）、QINGGUO（青果）等。
 */
enum class SyncProviderType {
    /** WakeUp 课程表口令导入/更新 */
    WAKEUP,
    /** 起迪教务系统（用户名+密码登录） */
    QIDI,
    /** 正方教务系统（用户名+密码登录） */
    ZF
}

/**
 * 凭据类型
 *
 * 表示加密存储的“密钥”语义：密码或令牌（口令/刷新令牌等）。
 */
enum class SyncCredentialType {
    /** 用户名 + 密码登录 */
    PASSWORD,
    /** 令牌/口令（如 WakeUp 口令、RefreshToken） */
    TOKEN
}

/**
 * 同步凭据
 *
 * 用于一键更新时的认证信息。出于安全考虑，所有字段都仅在内存与受信存储之间传输，
 * 不写入日志，不对外暴露。
 *
 * @property provider 同步提供者类型
 * @property type 凭据类型（密码/令牌）
 * @property username 用户名（当 type=PASSWORD 且提供者需要时）
 * @property secret 密钥：当 type=PASSWORD 时为密码，当 type=TOKEN 时为令牌/口令
 */
data class SyncCredentials(
    val provider: SyncProviderType,
    val type: SyncCredentialType,
    /** 用户名（当 type=PASSWORD 且提供者需要时） */
    val username: String? = null,
    /** 密钥（PASSWORD=密码；TOKEN=令牌/口令） */
    val secret: String,
    /** 可选：教务系统入口地址（如 https://jw.example.edu.cn） */
    val endpointUrl: String? = null
)

/**
 * 最近一次同步信息
 *
 * 用于 UI 显示“上次更新于 …”，以及故障排查。
 *
 * @property timestamp 同步时间戳（毫秒）
 * @property success 是否成功
 * @property message 结果描述（失败原因或成功统计）
 * @property provider 使用的同步提供者
 */
data class LastSyncInfo(
    val timestamp: Long = 0L,
    val success: Boolean = false,
    val message: String = "",
    val provider: SyncProviderType? = null
)

/**
 * 同步错误码
 *
 * 用于跨层传递失败原因，便于 UI 做精确提示与引导。
 */
enum class SyncErrorCode {
    /** 未找到已绑定凭据 */
    NO_CREDENTIALS,
    /** 认证失败（用户名/密码错误、令牌失效） */
    AUTH_FAILED,
    /** 网络错误（超时、连接失败） */
    NETWORK_ERROR,
    /** 数据解析错误 */
    PARSE_ERROR,
    /** 服务端异常或不兼容响应 */
    SERVER_ERROR,
    /** 未知错误 */
    UNKNOWN
}

/**
 * 一键同步结果
 *
 * @property updatedCount 本次写入/更新的课程数量
 * @property message 结果说明（如“新增 20 门课程”）
 * @property code 失败时的错误码
 */
sealed class TimetableSyncResult {
    data class Success(
        val updatedCount: Int,
        val message: String
    ) : TimetableSyncResult()

    data class Failure(
        val code: SyncErrorCode,
        val message: String
    ) : TimetableSyncResult()
}
