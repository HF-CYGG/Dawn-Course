package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.LocalBackupResult
import com.dawncourse.core.domain.repository.LocalBackupRepository
import javax.inject.Inject

/**
 * 导出本地备份用例
 *
 * 将导出逻辑封装为可测试的业务用例，供 UI 触发。
 */
class ExportLocalBackupUseCase @Inject constructor(
    private val repository: LocalBackupRepository
) {
    /**
     * 执行导出
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    suspend operator fun invoke(uri: String): LocalBackupResult {
        return repository.exportToUri(uri)
    }
}
