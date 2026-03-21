package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LocalBackupPreviewResult
import com.dawncourse.core.domain.repository.LocalBackupRepository
import javax.inject.Inject

/**
 * 读取本地备份预览用例
 *
 * 在执行还原前解析备份文件，用于展示备份时间与数据量。
 */
class ReadLocalBackupPreviewUseCase @Inject constructor(
    private val repository: LocalBackupRepository
) {
    /**
     * 执行预览读取
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    suspend operator fun invoke(uri: String): LocalBackupPreviewResult {
        return repository.readPreview(uri)
    }
}
