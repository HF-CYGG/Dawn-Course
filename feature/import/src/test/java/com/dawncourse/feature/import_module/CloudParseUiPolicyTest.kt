/**
 * 文件说明：验证云端解析失败后的用户提示策略。
 * 重点保证 cloud_empty_result 这类“服务端暂未产出结果”的情况只提示稍后重试，不直接向用户报错。
 */
package com.dawncourse.feature.import_module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudParseUiPolicyTest {

    @Test
    fun `cloud_empty_result 应提示稍后重试且不视为报错`() {
        val presentation = buildCloudParseFailurePresentation("cloud_empty_result")

        assertEquals("云端已接收数据，暂未生成解析结果，请过一段时间重试", presentation.userMessage)
        assertEquals("等待稍后重试", presentation.currentStep)
        assertTrue(presentation.isRetryable)
        assertFalse(presentation.shouldReportError)
    }

    @Test
    fun `普通云端失败仍保持错误提示`() {
        val presentation = buildCloudParseFailurePresentation("cloud_failed")

        assertEquals("云端解析失败：cloud_failed", presentation.userMessage)
        assertEquals("云端解析失败", presentation.currentStep)
        assertFalse(presentation.isRetryable)
        assertTrue(presentation.shouldReportError)
    }
}
