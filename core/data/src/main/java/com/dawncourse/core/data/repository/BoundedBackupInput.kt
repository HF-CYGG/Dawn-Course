package com.dawncourse.core.data.repository

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 普通 SAF/WebDAV 与启动恢复共用同级别的备份输入内存上限。 */
internal object BoundedBackupInput {
    const val MAX_BACKUP_BYTES: Int = 32 * 1024 * 1024

    /** 不信任 Content-Length；读到上限后额外一个字节就 fail-closed。 */
    fun readUtf8(
        input: InputStream,
        maxBytes: Int = MAX_BACKUP_BYTES,
    ): String {
        require(maxBytes > 0) { "备份读取上限必须为正数" }
        val output = ByteArrayOutputStream(minOf(8 * 1024, maxBytes))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) throw BackupInputTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

internal class BackupInputTooLargeException : IllegalArgumentException("备份文件超过读取上限")
