package com.dawncourse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 文件说明：Profile 根 Flow 失败不能永久停留在 Loading。 */
class ProfileFlowFailurePolicyTest {

    @Test
    fun `摘要流失败映射为显式安全状态`() {
        assertEquals(ProfileSummaryLoadState.Failed, profileSummaryFailureState())
    }

    @Test
    fun `根数据失败时进入只读态`() {
        val state = ProfileManagementUiState(isLoading = false, hasLoadError = true)

        assertFalse(state.canMutate)
    }
}
