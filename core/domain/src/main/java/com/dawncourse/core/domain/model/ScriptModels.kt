package com.dawncourse.core.domain.model

/**
 * Cloud script manifest returned by the script registry.
 */
data class ScriptManifest(
    val manifestVersion: Int,
    val generatedAt: Long,
    val minClientVersionCode: Long,
    val scripts: List<RemoteScriptDescriptor>,
    val signature: String
)

/**
 * Descriptor for one remotely published script.
 */
data class RemoteScriptDescriptor(
    val scriptId: String,
    val category: String,
    val name: String,
    val version: Int,
    val releaseId: String,
    val channel: String,
    val url: String,
    val metaUrl: String,
    val sha256: String,
    val signature: String,
    val alg: String,
    val priority: Int,
    val schoolSystemTypes: List<String>,
    val schoolIds: List<String>,
    val rolloutPercent: Int,
    val killSwitch: Boolean,
    val minAppVersionCode: Long,
    val maxAppVersionCode: Long?,
    val parserApiVersion: Int,
    val dependencies: List<ScriptDependency>,
    val changelog: String
)

data class ScriptDependency(
    val category: String,
    val name: String,
    val version: Int
)
