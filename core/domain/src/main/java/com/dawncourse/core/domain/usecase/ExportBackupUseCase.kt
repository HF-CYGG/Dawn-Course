package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.BackupResult
import com.dawncourse.core.domain.repository.BackupRepository
import java.io.OutputStream
import javax.inject.Inject

/**
 * 导出备份用例
 */
class ExportBackupUseCase @Inject constructor(
    /** 备份仓库 */
    private val repository: BackupRepository
) {
    /**
     * 执行导出
     *
     * @param outputStream 输出流
     */
    suspend operator fun invoke(outputStream: OutputStream): BackupResult {
        return repository.exportBackup(outputStream)
    }
}
