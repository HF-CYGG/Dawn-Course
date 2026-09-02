package com.dawncourse.app

/** Recovery/Blocked 必须先撤下系统表面数据，再等待可能阻塞的快照物理删除。 */
internal object DatabaseRecoverySurfaceTransition {
    suspend fun execute(
        publishSafeSystemSurface: () -> Unit,
        invalidateSnapshot: suspend () -> Unit,
    ) {
        publishSafeSystemSurface()
        invalidateSnapshot()
    }
}
