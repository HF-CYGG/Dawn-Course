package com.dawncourse.core.domain.repository

/**
 * 日历导出仓库接口
 *
 * 提供将日历数据（如 ICS 格式字符串）写入到指定 URI（如 SAF 选择的路径）的能力。
 */
interface CalendarExportRepository {
    /**
     * 将 ICS 字符串写入指定 URI
     *
     * @param uri SAF 返回的文件 URI 字符串
     * @param icsContent 生成的 ICS 格式文本内容
     * @return 写入是否成功
     */
    suspend fun exportIcsToUri(uri: String, icsContent: String): Boolean
}
