package com.dawncourse.feature.import_module

import org.junit.Assert.assertEquals
import org.junit.Test

class JavaScriptEscapingTest {
    @Test
    fun `escapes credentials embedded in single quoted script literals`() {
        assertEquals(
            "a\\'b\\\\c\\nd\\r\\u2028\\u2029",
            escapeJavaScriptSingleQuoted("a'b\\c\nd\r\u2028\u2029")
        )
    }
}
