package com.dawncourse.feature.widget.startup

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget 启动初始化器依赖测试。
 */
class WidgetSyncInitializerTest {

    @Test
    fun `初始化器由 Application 手动启动且不声明自动依赖`() {
        val dependencies = WidgetSyncInitializer().dependencies()

        assertTrue(dependencies.isEmpty())
    }
}
