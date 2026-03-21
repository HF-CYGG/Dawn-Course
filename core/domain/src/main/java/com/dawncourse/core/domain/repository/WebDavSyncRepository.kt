package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.WebDavSyncResult

/**
 * WebDAV 同步仓库接口
 *
 * 负责云端备份的查询、上传与下载恢复。
 */
interface WebDavSyncRepository {
    /**
     * 获取云端备份信息
     */
    suspend fun fetchRemoteInfo(): WebDavSyncResult

    /**
     * 上传本地备份
     *
     * @param forceUpload 是否强制覆盖云端
     */
    suspend fun uploadBackup(forceUpload: Boolean): WebDavSyncResult

    /**
     * 下载云端备份并恢复
     */
    suspend fun downloadBackup(): WebDavSyncResult
}
