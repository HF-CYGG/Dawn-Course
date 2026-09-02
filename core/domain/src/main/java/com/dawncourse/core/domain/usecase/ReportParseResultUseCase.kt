package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.ParseReportPayload
import com.dawncourse.core.domain.repository.ParseReportRepository
import javax.inject.Inject

class ReportParseResultUseCase @Inject constructor(
    private val repository: ParseReportRepository
) {
    suspend operator fun invoke(payload: ParseReportPayload): Result<Unit> {
        return repository.reportParseResult(payload)
    }
}
