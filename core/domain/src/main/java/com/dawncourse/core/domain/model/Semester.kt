package com.dawncourse.core.domain.model

data class Semester(
    val id: Long = 0,
    val name: String, // e.g., "2023秋"
    val startDate: Long, // Timestamp of the first Monday 00:00
    val weekCount: Int = 20,
    val isCurrent: Boolean = false
)
