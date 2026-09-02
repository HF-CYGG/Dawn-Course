package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.ParseReportPayload

interface ParseReportRepository {
    suspend fun reportParseResult(payload: ParseReportPayload): Result<Unit>
}
