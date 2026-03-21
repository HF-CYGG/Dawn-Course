package com.dawncourse.core.domain.usecase

import com.dawncourse.core.domain.model.WebDavSyncResult
import com.dawncourse.core.domain.repository.WebDavSyncRepository
import javax.inject.Inject

/**
 * 获取 WebDAV 云端备份信息的用例
 *
 * 负责从仓库层拉取云端备份的元信息（是否存在/更新时间等），
 * 供 UI 做状态展示与冲突提示。
 */
class FetchWebDavRemoteInfoUseCase @Inject constructor(
    private val repository: WebDavSyncRepository
) {
    /**
     * 执行获取云端元信息
     */
    suspend operator fun invoke(): WebDavSyncResult {
        return repository.fetchRemoteInfo()
    }
}
