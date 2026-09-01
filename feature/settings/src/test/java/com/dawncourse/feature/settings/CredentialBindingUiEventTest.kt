package com.dawncourse.feature.settings

import com.dawncourse.core.domain.repository.CredentialBindingMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CredentialBindingUiEventTest {
    @Test
    fun `成功 失败与不一致分别映射为明确 UI 事件`() {
        assertEquals(
            CredentialBindingUiEvent.Saved,
            credentialBindingUiEvent(
                CredentialBindingMutationResult.Success("binding"),
                CredentialBindingUiEvent.Saved,
            ),
        )
        assertEquals(
            CredentialBindingUiEvent.Rejected,
            credentialBindingUiEvent(
                CredentialBindingMutationResult.Rejected("rejected"),
                CredentialBindingUiEvent.Saved,
            ),
        )
        assertEquals(
            CredentialBindingUiEvent.Inconsistent,
            credentialBindingUiEvent(
                CredentialBindingMutationResult.Inconsistent("inconsistent"),
                CredentialBindingUiEvent.Saved,
            ),
        )
    }

    @Test
    fun `解绑反馈只由异步语义事件驱动`() {
        val source = File(
            "src/main/java/com/dawncourse/feature/settings/SettingsScreen.kt",
        ).readText()

        assertTrue(source.contains("viewModel.credentialBindingEvents.collect"))
        assertFalse(source.contains("Toast.makeText(context, \"已解绑\""))
    }
}
