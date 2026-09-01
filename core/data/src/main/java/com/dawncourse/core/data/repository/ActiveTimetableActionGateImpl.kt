package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.ActiveTimetableContext
import com.dawncourse.core.domain.repository.ActiveTimetableActionGate
import javax.inject.Inject
import javax.inject.Singleton

/** Profile 选择锁的领域适配器，Feature 无需依赖 core:data 实现类。 */
@Singleton
class ActiveTimetableActionGateImpl @Inject constructor(
    private val coordinator: ProfileSelectionCoordinator,
) : ActiveTimetableActionGate {
    override suspend fun <T> executeIfActive(
        profileId: Long,
        semesterId: Long,
        action: suspend (ActiveTimetableContext) -> T,
    ): T? = coordinator.executeActiveAction(profileId, semesterId, action)
}
