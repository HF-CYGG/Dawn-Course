package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.RestoreResult
import com.dawncourse.core.domain.repository.BackupRepository
import java.io.InputStream
import javax.inject.Inject

/**
 * 导入备份用例
 */
class ImportBackupUseCase @Inject constructor(
    /** 备份仓库 */
    private val repository: BackupRepository
) {
    /**
     * 执行导入
     *
     * @param inputStream 输入流
     */
    suspend operator fun invoke(inputStream: InputStream): RestoreResult {
        return repository.importBackup(inputStream)
    }
}
