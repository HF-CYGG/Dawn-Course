package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.repository.WebDavSyncRepository
import javax.inject.Inject

/**
 * 下载 WebDAV 备份的用例
 *
 * 负责从云端获取备份并触发本地恢复。
 */
class DownloadWebDavBackupUseCase @Inject constructor(
    private val repository: WebDavSyncRepository
) {
    /**
     * 执行下载并恢复
     */
    suspend operator fun invoke(): WebDavSyncResult {
        return repository.downloadBackup()
    }
}
