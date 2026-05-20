package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.ScriptManifest

interface ScriptManifestRepository {
    suspend fun fetchManifest(
        schoolId: String? = null,
        schoolSystemType: String? = null,
        appVersionCode: Long
    ): Result<ScriptManifest>
}
