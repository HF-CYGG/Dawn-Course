package com.dawncourse.core.domain.model

enum class SyncProviderType {
    WAKEUP,
    QIDI,
    ZF
}

enum class SyncCredentialType {
    PASSWORD,
    TOKEN
}

data class SyncCredentials(
    val provider: SyncProviderType,
    val type: SyncCredentialType,
    val username: String = "",
    val secret: String = "",
    val endpointUrl: String = ""
)

data class LastSyncInfo(
    val timestamp: Long = 0L,
    val success: Boolean = false,
    val message: String = "",
    val provider: SyncProviderType? = null
)

sealed class TimetableSyncResult(open val message: String) {
    data class Success(override val message: String) : TimetableSyncResult(message)
    data class Failure(override val message: String) : TimetableSyncResult(message)
}
