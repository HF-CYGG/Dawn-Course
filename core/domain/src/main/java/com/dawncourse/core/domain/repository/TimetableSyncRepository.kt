package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.TimetableSyncResult

interface TimetableSyncRepository {
    suspend fun syncCurrentSemester(): TimetableSyncResult
}
