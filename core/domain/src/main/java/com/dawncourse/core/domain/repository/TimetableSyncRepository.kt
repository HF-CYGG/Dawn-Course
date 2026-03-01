package com.dawncourse.core.domain.repository

import com.dawncourse.core.domain.model.TimetableSyncResult

/**
 * 课表同步仓库
 *
 * 封装“一键更新”核心流程：读取凭据→认证（或续期）→拉取远端课表→转换领域模型→落库。
 * 具体实现交由数据层完成，Domain 层仅定义契约。
 */
interface TimetableSyncRepository {
    /**
     * 同步当前学期课表
     *
     * 应确保数据库写入具备事务性；返回结构化结果用于 UI 提示。
     */
    suspend fun syncCurrentSemester(): TimetableSyncResult
}

