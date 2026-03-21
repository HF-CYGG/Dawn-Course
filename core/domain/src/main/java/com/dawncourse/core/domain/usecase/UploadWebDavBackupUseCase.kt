package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.repository.WebDavSyncRepository
import javax.inject.Inject

/**
 * 上传 WebDAV 备份的用例
 *
 * 负责触发本地数据打包与上传逻辑，并支持强制覆盖上传。
 */
class UploadWebDavBackupUseCase @Inject constructor(
    private val repository: WebDavSyncRepository
) {
    /**
     * 执行上传
     *
     * @param forceUpload 是否强制覆盖云端备份
     */
    suspend operator fun invoke(forceUpload: Boolean): WebDavSyncResult {
        return repository.uploadBackup(forceUpload)
    }
}
