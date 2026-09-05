package com.dawncourse.feature.import_module

import org.junit.Assert.assertEquals
import org.junit.Test

/** 文件说明：导入外部 I/O 异常不能把底层错误或地址呈现给用户。 */
class ImportFailurePolicyTest {

    @Test
    fun `导入异常使用固定安全提示`() {
        assertEquals("导入失败，请稍后重试", importOperationFailureText())
    }
}
