package com.dawncourse.app

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MainUiEventDeliveryTest {
    @Test fun `startup event sent before activity subscription is delivered`() = runBlocking {
        val channel = Channel<MainUiEvent>(Channel.BUFFERED)
        channel.send(MainUiEvent.MuteRecoveryOperationFailed)

        assertEquals(MainUiEvent.MuteRecoveryOperationFailed, mainUiEventFlow(channel).first())
    }

    @Test
    fun `mute recovery flow exception becomes safe empty state and event`() = runBlocking {
        var failures = 0

        val recovered = recoverMuteRecoveryFlow(
            upstream = flow { throw IllegalStateException("sensitive") },
            onFailure = { failures += 1 },
        ).first()

        assertEquals(emptyList<Any>(), recovered)
        assertEquals(1, failures)
    }

    @Test(expected = AssertionError::class)
    fun `mute recovery flow does not swallow errors`() {
        runBlocking {
            recoverMuteRecoveryFlow(
                upstream = flow { throw AssertionError() },
                onFailure = {},
            ).first()
        }
    }
}
