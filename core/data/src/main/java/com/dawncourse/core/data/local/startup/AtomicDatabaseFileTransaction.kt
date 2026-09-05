package com.dawncourse.core.data.local.startup

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 数据库迁移共用的底层文件事务原语。
 *
 * 它只负责锁、不可变副本、fsync、同目录原子替换和固定 sidecar 归档；明文迁移与 rekey
 * 各自保留独立 journal 和状态机，避免复用错误的恢复语义。
 */
internal class AtomicDatabaseFileTransaction(
    private val databaseFile: File,
    private val lockFile: File
) {
    private val directory = requireNotNull(databaseFile.absoluteFile.parentFile)
    private val localLock = processLocks.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }

    fun <T> withExclusiveLock(block: () -> T): T {
        directory.mkdirs()
        localLock.lock()
        try {
            return FileOutputStream(lockFile, true).channel.use { channel ->
                channel.lock().use { block() }
            }
        } finally {
            localLock.unlock()
        }
    }

    fun copyImmutable(source: File, target: File) {
        require(source.isFile) { "数据库事务源文件不存在" }
        require(!target.exists()) { "数据库事务目标文件已存在" }
        val copying = File(target.path + ".copying")
        require(!copying.exists()) { "数据库事务复制临时文件已存在" }
        Files.copy(source.toPath(), copying.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        forceFile(copying)
        atomicMove(copying, target, replaceExisting = false)
        forceDirectoryBestEffort()
    }

    fun atomicReplace(source: File, target: File) {
        require(source.isFile) { "数据库事务换入源不存在" }
        atomicMove(source, target, replaceExisting = true)
        forceDirectoryBestEffort()
    }

    fun archiveSidecars(sourceBase: File, archiveBase: File, marker: String) {
        SIDECAR_SUFFIXES.forEach { suffix ->
            val source = File(sourceBase.path + suffix)
            if (source.exists()) {
                val archive = File(archiveBase.path + suffix + marker)
                require(!archive.exists()) { "sidecar 归档文件已存在" }
                atomicMove(source, archive, replaceExisting = false)
            }
        }
    }

    fun requireNoHotSidecars(database: File) {
        listOf("-wal", "-journal").forEach { suffix ->
            val sidecar = File(database.path + suffix)
            require(!sidecar.exists() || sidecar.length() == 0L) { "数据库存在未合并 sidecar" }
        }
    }

    fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        atomicMove(temporary, target, replaceExisting = true)
        forceDirectoryBestEffort()
    }

    fun deletePrivateArtifact(file: File) {
        if (file.exists()) require(file.delete()) { "无法清理数据库事务遗留文件" }
    }

    fun forceDirectoryBestEffort() {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun atomicMove(source: File, target: File, replaceExisting: Boolean) {
        val options = if (replaceExisting) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), target.toPath(), *options)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("当前文件系统不支持数据库原子换入", unsupported)
        }
    }

    private fun forceFile(file: File) {
        FileOutputStream(file, true).channel.use { it.force(true) }
    }

    private companion object {
        val processLocks = ConcurrentHashMap<String, ReentrantLock>()
        val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}
