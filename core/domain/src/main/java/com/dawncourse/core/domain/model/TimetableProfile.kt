package com.dawncourse.core.domain.model

/** 一套彼此独立的课表聚合；[uuid] 是跨设备交换身份。 */
data class TimetableProfile(
    val id: Long = 0,
    val uuid: String,
    val name: String,
    val activeSemesterId: Long? = null,
    val lastUsedAt: Long = 0L,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)
