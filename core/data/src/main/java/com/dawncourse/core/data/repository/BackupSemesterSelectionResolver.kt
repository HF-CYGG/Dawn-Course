package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.Semester

/**
 * 备份恢复后的当前学期选择策略。
 *
 * 只有 v1 缺少选择字段时才兼容旧 isCurrent 标记；v2 的 0、null 或无效 ID
 * 都表示不重新激活旧标记，避免用户明确清空后被意外恢复。
 */
internal object BackupSemesterSelectionResolver {
    /** 返回恢复后应选择的学期 ID；无可用选择时返回 null。 */
    fun resolve(version: Int, requestedSemesterId: Long?, semesters: List<Semester>): Long? {
        val existingIds = semesters.asSequence().map { it.id }.filter { it > 0L }.toSet()
        val requested = requestedSemesterId?.takeIf { it > 0L && it in existingIds }
        if (requested != null) return requested

        if (version != 1 || requestedSemesterId != null) return null

        return semesters.asSequence()
            .filter { it.isCurrent && it.id in existingIds }
            .map { it.id }
            .minOrNull()
    }
}
