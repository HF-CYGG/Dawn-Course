package com.dawncourse.feature.settings

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsUiEventDeliveryTest {
    @Test fun `operation failure sent before screen collection is delivered`() = runBlocking {
        val channel = Channel<SettingsUiEvent>(Channel.BUFFERED)
        channel.send(SettingsUiEvent.OperationFailed)
        assertEquals(SettingsUiEvent.OperationFailed, settingsUiEventFlow(channel).first())
    }
}
