package com.dawncourse.core.data.repository

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** P0-5 Recovery 页可消费的持久化恢复标记。 */
@Singleton
class BackupRecoveryRequiredStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val markerFile = AtomicFile(
        File(context.noBackupFilesDir, "recovery/backup_restore_required")
    )

    suspend fun markRequired() = withContext(Dispatchers.IO) {
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

    fun isRequired(): Boolean = markerFile.baseFile.exists()
}
