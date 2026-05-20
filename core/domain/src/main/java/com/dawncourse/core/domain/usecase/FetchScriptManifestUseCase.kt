package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.ScriptManifest
import com.dawncourse.core.domain.repository.ScriptManifestRepository
import javax.inject.Inject

class FetchScriptManifestUseCase @Inject constructor(
    private val repository: ScriptManifestRepository
) {
    suspend operator fun invoke(
        schoolId: String? = null,
        schoolSystemType: String? = null,
        appVersionCode: Long
    ): Result<ScriptManifest> {
        return repository.fetchManifest(
            schoolId = schoolId,
            schoolSystemType = schoolSystemType,
            appVersionCode = appVersionCode
        )
    }
}
