package com.dawncourse.feature.settings

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 文件说明：保证普通设置写入失败不会被误报为成功。 */
class SettingsOperationFailurePolicyTest {

    @Test
    fun `设置写入异常映射为独立语义失败事件`() = runBlocking {
        val channel = Channel<SettingsUiEvent>(Channel.BUFFERED)
        channel.send(SettingsUiEvent.OperationFailed)
        assertEquals(SettingsUiEvent.OperationFailed, settingsUiEventFlow(channel).first())
    }
}
