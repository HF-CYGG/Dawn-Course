package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.BackupResult
import com.dawncourse.core.domain.model.RestoreResult
import java.io.InputStream
import java.io.OutputStream

/**
 * 备份与还原仓库接口
 */
interface BackupRepository {
    /**
     * 导出备份数据
     *
     * @param outputStream 输出流
     */
    suspend fun exportBackup(outputStream: OutputStream): BackupResult

    /**
     * 导入备份数据
     *
     * @param inputStream 输入流
     */
    suspend fun importBackup(inputStream: InputStream): RestoreResult
}
