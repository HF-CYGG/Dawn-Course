package com.dawncourse.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件说明：锁定更新检查失败时手动与自动入口的可见状态边界。
 */
class UpdateFailurePolicyTest {

    /** 手动检查失败应显示不包含底层异常内容的通用错误。 */
    @Test
    fun `手动检查失败显示安全错误状态`() {
        val state = updateFailureState(isManual = true)

        assertTrue(state is UpdateUiState.Error)
        assertEquals("检查更新失败，请检查网络或稍后重试", (state as UpdateUiState.Error).message)
    }

    /** 自动检查失败不能阻塞首页，也不应冒泡底层网络错误。 */
    @Test
    fun `自动检查失败回到空闲状态`() {
        assertEquals(UpdateUiState.Idle, updateFailureState(isManual = false))
    }
}
