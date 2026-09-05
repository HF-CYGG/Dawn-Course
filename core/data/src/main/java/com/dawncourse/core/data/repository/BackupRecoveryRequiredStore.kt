package com.dawncourse.core.data.repository

import android.content.Context
import android.util.AtomicFile
import com.dawncourse.core.data.local.startup.AtomicFileArtifactProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** P0-5 Recovery 页可消费的持久化恢复标记。 */
internal interface BackupRecoveryRequiredMarker {
    fun markRequired()

    fun isRequired(): Boolean

    fun clearRequiredAndConfirm()
}

/** 备份恢复 marker 的 Android AtomicFile 实现，所有残留均按恢复责任处理。 */
internal class AndroidBackupRecoveryRequiredMarker(
    file: File,
) : BackupRecoveryRequiredMarker {
    private val markerFile = AtomicFile(file)

    override fun markRequired() {
        markerFile.baseFile.parentFile?.mkdirs()
        val output = markerFile.startWrite()
        try {
            val marker = "version=1\nreason=BACKUP_RESTORE_COMPENSATION_FAILED\ntimestamp=${System.currentTimeMillis()}\n"
            output.write(marker.toByteArray(Charsets.UTF_8))
            markerFile.finishWrite(output)
        } catch (error: Throwable) {
            markerFile.failWrite(output)
            throw error
        }
    }

    override fun isRequired(): Boolean = try {
        AtomicFileArtifactProtocol.readOrNull(markerFile)?.also { bytes -> bytes.fill(0) } != null
    } catch (_: Throwable) {
        true
    }

    override fun clearRequiredAndConfirm() {
        AtomicFileArtifactProtocol.deleteAndConfirm(markerFile)
    }
}

@Singleton
class BackupRecoveryRequiredStore internal constructor(
    private val marker: BackupRecoveryRequiredMarker,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        AndroidBackupRecoveryRequiredMarker(
            File(context.noBackupFilesDir, "recovery/backup_restore_required"),
        ),
    )

    suspend fun markRequired() = withContext(Dispatchers.IO) {
        marker.markRequired()
    }

    fun isRequired(): Boolean = marker.isRequired()

    /**
     * 恢复成功后清除 marker 并确认，不允许把删除失败伪装成恢复提交成功。
     * 同步调用，供数据库启动临界区在 IO 线程内直接使用。
     */
    fun clearRequired() {
        marker.clearRequiredAndConfirm()
    }
}
