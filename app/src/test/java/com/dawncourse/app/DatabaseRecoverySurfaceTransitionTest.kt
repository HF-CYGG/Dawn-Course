package com.dawncourse.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseRecoverySurfaceTransitionTest {
    @Test
    fun `快照删除阻塞前已同步发布安全系统表面`() = runBlocking {
        var safeSurfacePublished = false
        val invalidationEntered = CompletableDeferred<Unit>()
        val releaseInvalidation = CompletableDeferred<Unit>()

        val transition = launch {
            DatabaseRecoverySurfaceTransition.execute(
                publishSafeSystemSurface = { safeSurfacePublished = true },
                invalidateSnapshot = {
                    invalidationEntered.complete(Unit)
                    releaseInvalidation.await()
                },
            )
        }

        invalidationEntered.await()
        assertTrue(safeSurfacePublished)
        releaseInvalidation.complete(Unit)
        transition.join()
    }
}
