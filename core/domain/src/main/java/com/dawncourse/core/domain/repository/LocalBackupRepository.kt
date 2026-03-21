package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.LocalBackupPreviewResult
import com.dawncourse.core.domain.model.LocalBackupResult

/**
 * 本地备份仓库接口
 *
 * 约束本地备份/还原的核心能力，避免 UI 直接接触具体存储实现。
 */
interface LocalBackupRepository {
    /**
     * 导出备份数据到指定 URI
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    suspend fun exportToUri(uri: String): LocalBackupResult

    /**
     * 从指定 URI 导入备份数据
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    suspend fun importFromUri(uri: String): LocalBackupResult

    /**
     * 读取备份预览信息
     *
     * @param uri SAF 返回的文件 URI 字符串
     */
    suspend fun readPreview(uri: String): LocalBackupPreviewResult
}
