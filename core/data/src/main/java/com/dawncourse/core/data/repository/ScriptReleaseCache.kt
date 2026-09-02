package com.dawncourse.core.data.repository

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** 缓存中的一个不可变脚本 release。 */
data class CachedScriptRelease(
    val scriptKey: String,
    val releaseId: String,
    val content: String,
    val metaRaw: String,
    val dependencies: List<String>
)

/**
 * 按 scriptKey 隔离的脚本 release 缓存。
 *
 * staging 只保存待试跑版本；active 与 previous_stable 通过原子指针切换，
 * 保证下载或试跑失败不会提前覆盖当前可用脚本。
 */
class ScriptReleaseCache(private val rootDirectory: File) {

    /** 将已验签 release 写入 staging，不改变 active。 */
    @Synchronized
    fun stage(release: CachedScriptRelease) {
        val track = trackDirectory(release.scriptKey)
        val stagingRoot = File(track, STAGING_DIRECTORY).apply { mkdirs() }
        val target = File(stagingRoot, releaseDirectoryName(release.releaseId))
        val temporary = File(stagingRoot, ".tmp-${releaseDirectoryName(release.releaseId)}")
        temporary.deleteRecursively()
        writeRelease(temporary, release)
        target.deleteRecursively()
        moveAtomically(temporary, target)
    }

    /** 读取指定 staging release。 */
    @Synchronized
    fun readStaging(scriptKey: String, releaseId: String): CachedScriptRelease? {
        return readRelease(File(File(trackDirectory(scriptKey), STAGING_DIRECTORY), releaseDirectoryName(releaseId)))
    }

    /** 读取当前 active release。 */
    @Synchronized
    fun readActive(scriptKey: String): CachedScriptRelease? {
        return readPointedRelease(scriptKey, ACTIVE_POINTER)
    }

    /** 读取唯一保留的 previous stable release。 */
    @Synchronized
    fun readPreviousStable(scriptKey: String): CachedScriptRelease? {
        return readPointedRelease(scriptKey, PREVIOUS_POINTER)
    }

    /** 同一不可变 release 与内容 hash 已处于 active 时无需再次下载或写盘。 */
    @Synchronized
    fun matchesActive(scriptKey: String, releaseId: String, expectedSha256: String): Boolean {
        if (releaseId.isBlank() || expectedSha256.isBlank()) return false
        val active = readActive(scriptKey) ?: return false
        return active.releaseId == releaseId && sha256(active.content).equals(expectedSha256, ignoreCase = true)
    }

    /** 标记某个 category/name 已由 V2 作用域缓存接管。 */
    @Synchronized
    fun markLegacySuperseded(category: String, name: String) {
        val markerDirectory = File(rootDirectory, LEGACY_MARKER_DIRECTORY).apply { mkdirs() }
        val marker = File(markerDirectory, sha256("$category/$name"))
        if (!marker.exists()) marker.writeText("v2", Charsets.UTF_8)
    }

    /** V2 接管后不再读取无作用域 current/previous 文件。 */
    @Synchronized
    fun isLegacySuperseded(category: String, name: String): Boolean {
        return File(File(rootDirectory, LEGACY_MARKER_DIRECTORY), sha256("$category/$name")).isFile
    }

    /**
     * 将已试跑成功的 staging release 原子提升为 active。
     *
     * @return staging 存在且激活成功时返回 true。
     */
    @Synchronized
    fun activate(scriptKey: String, releaseId: String): Boolean {
        val track = trackDirectory(scriptKey)
        val staging = File(File(track, STAGING_DIRECTORY), releaseDirectoryName(releaseId))
        val stagedRelease = readRelease(staging) ?: return false
        if (stagedRelease.scriptKey != scriptKey || stagedRelease.releaseId != releaseId) return false
        val releasesRoot = File(track, RELEASES_DIRECTORY).apply { mkdirs() }
        val target = File(releasesRoot, releaseDirectoryName(releaseId))
        if (!target.exists()) moveAtomically(staging, target) else staging.deleteRecursively()

        val currentReleaseId = readPointer(track, ACTIVE_POINTER)
        val previousReleaseId = readPointer(track, PREVIOUS_POINTER)
        if (!currentReleaseId.isNullOrBlank() && currentReleaseId != releaseId) {
            writePointer(track, PREVIOUS_POINTER, currentReleaseId)
        }
        writePointer(track, ACTIVE_POINTER, releaseId)

        if (!previousReleaseId.isNullOrBlank() &&
            previousReleaseId != currentReleaseId &&
            previousReleaseId != releaseId
        ) {
            File(releasesRoot, releaseDirectoryName(previousReleaseId)).deleteRecursively()
        }
        return true
    }

    /** 将失败 staging 移入 quarantine，保留 active。 */
    @Synchronized
    fun quarantine(scriptKey: String, releaseId: String, reason: String): Boolean {
        val track = trackDirectory(scriptKey)
        val staging = File(File(track, STAGING_DIRECTORY), releaseDirectoryName(releaseId))
        if (!staging.exists()) return false
        val quarantineRoot = File(track, QUARANTINE_DIRECTORY).apply { mkdirs() }
        val target = File(quarantineRoot, releaseDirectoryName(releaseId))
        target.deleteRecursively()
        moveAtomically(staging, target)
        File(target, QUARANTINE_REASON_FILE).writeText(reason.take(120), Charsets.UTF_8)
        return true
    }

    /** active 真实解析失败时恢复 previous stable，并隔离失败 release。 */
    @Synchronized
    fun rollbackActive(scriptKey: String, releaseId: String, reason: String): Boolean {
        val track = trackDirectory(scriptKey)
        val currentReleaseId = readPointer(track, ACTIVE_POINTER) ?: return false
        if (currentReleaseId != releaseId) return false
        val previousReleaseId = readPointer(track, PREVIOUS_POINTER) ?: return false
        val releasesRoot = File(track, RELEASES_DIRECTORY)
        val previous = File(releasesRoot, releaseDirectoryName(previousReleaseId))
        if (readRelease(previous) == null) return false
        val failed = File(releasesRoot, releaseDirectoryName(releaseId))
        if (readRelease(failed) == null) return false

        val quarantineRoot = File(track, QUARANTINE_DIRECTORY).apply { mkdirs() }
        val quarantine = File(quarantineRoot, releaseDirectoryName(releaseId))
        quarantine.deleteRecursively()
        if (!failed.copyRecursively(quarantine, overwrite = true)) {
            quarantine.deleteRecursively()
            return false
        }
        File(quarantine, QUARANTINE_REASON_FILE).writeText(reason.take(120), Charsets.UTF_8)

        writePointer(track, ACTIVE_POINTER, previousReleaseId)
        File(track, PREVIOUS_POINTER).delete()
        failed.deleteRecursively()
        return true
    }

    /** 判断 release 是否已经被隔离。 */
    @Synchronized
    fun isQuarantined(scriptKey: String, releaseId: String): Boolean {
        return File(File(trackDirectory(scriptKey), QUARANTINE_DIRECTORY), releaseDirectoryName(releaseId)).exists()
    }

    /** 通过指针读取 releases 中的不可变内容。 */
    private fun readPointedRelease(scriptKey: String, pointerName: String): CachedScriptRelease? {
        val track = trackDirectory(scriptKey)
        val releaseId = readPointer(track, pointerName) ?: return null
        return readRelease(File(File(track, RELEASES_DIRECTORY), releaseDirectoryName(releaseId)))
    }

    /** 写入 release 内容与结构化元数据。 */
    private fun writeRelease(directory: File, release: CachedScriptRelease) {
        directory.mkdirs()
        File(directory, SCRIPT_FILE).writeText(release.content, Charsets.UTF_8)
        File(directory, META_FILE).writeText(release.metaRaw, Charsets.UTF_8)
        val dependencyDirectory = File(directory, DEPENDENCIES_DIRECTORY).apply { mkdirs() }
        release.dependencies.forEachIndexed { index, content ->
            File(dependencyDirectory, "$index.js").writeText(content, Charsets.UTF_8)
        }
        val properties = Properties().apply {
            setProperty("scriptKey", release.scriptKey)
            setProperty("releaseId", release.releaseId)
            setProperty("dependencyCount", release.dependencies.size.toString())
        }
        File(directory, RELEASE_PROPERTIES).outputStream().use { output ->
            properties.store(output, null)
        }
    }

    /** 从磁盘读取 release，缺少任一关键文件时视为不可用。 */
    private fun readRelease(directory: File): CachedScriptRelease? {
        if (!directory.isDirectory) return null
        return runCatching {
            val properties = Properties().apply {
                File(directory, RELEASE_PROPERTIES).inputStream().use(::load)
            }
            val dependencyCount = properties.getProperty("dependencyCount", "0").toIntOrNull() ?: 0
            val dependencyDirectory = File(directory, DEPENDENCIES_DIRECTORY)
            CachedScriptRelease(
                scriptKey = properties.getProperty("scriptKey").orEmpty(),
                releaseId = properties.getProperty("releaseId").orEmpty(),
                content = File(directory, SCRIPT_FILE).readText(Charsets.UTF_8),
                metaRaw = File(directory, META_FILE).takeIf(File::exists)?.readText(Charsets.UTF_8).orEmpty(),
                dependencies = (0 until dependencyCount).map { index ->
                    File(dependencyDirectory, "$index.js").readText(Charsets.UTF_8)
                }
            )
        }.getOrNull()?.takeIf { it.scriptKey.isNotBlank() && it.releaseId.isNotBlank() && it.content.isNotBlank() }
    }

    /** 原子更新 active 或 previous 指针。 */
    private fun writePointer(track: File, name: String, releaseId: String) {
        track.mkdirs()
        val target = File(track, name)
        val temporary = File(track, ".$name.tmp")
        temporary.writeText(releaseId, Charsets.UTF_8)
        moveAtomically(temporary, target)
    }

    /** 读取指针中的 releaseId。 */
    private fun readPointer(track: File, name: String): String? {
        return File(track, name).takeIf(File::isFile)?.readText(Charsets.UTF_8)?.trim()?.takeIf(String::isNotBlank)
    }

    /** 使用 scriptKey 哈希生成稳定且不泄露学校名称的目录。 */
    private fun trackDirectory(scriptKey: String): File {
        return File(rootDirectory, sha256(scriptKey)).apply { mkdirs() }
    }

    /** 使用 releaseId 哈希阻止路径穿越。 */
    private fun releaseDirectoryName(releaseId: String): String = sha256(releaseId)

    /** 优先使用原子移动，不支持时回落到同文件系统替换。 */
    private fun moveAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 计算目录键。 */
    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val STAGING_DIRECTORY = "staging"
        const val RELEASES_DIRECTORY = "releases"
        const val QUARANTINE_DIRECTORY = "quarantine"
        const val DEPENDENCIES_DIRECTORY = "dependencies"
        const val SCRIPT_FILE = "script.js"
        const val META_FILE = "meta.json"
        const val RELEASE_PROPERTIES = "release.properties"
        const val QUARANTINE_REASON_FILE = "reason.txt"
        const val ACTIVE_POINTER = "active.pointer"
        const val PREVIOUS_POINTER = "previous_stable.pointer"
        const val LEGACY_MARKER_DIRECTORY = "legacy_superseded"
    }
}
