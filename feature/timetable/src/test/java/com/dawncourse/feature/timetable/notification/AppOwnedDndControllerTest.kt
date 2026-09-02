package com.dawncourse.feature.timetable.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOwnedDndControllerTest {

    @Test
    fun `Android 15 以上只用 PRIORITY 和 ALL 管理应用隐式规则`() {
        val platform = FakeDndPlatform(sdkInt = 35, hasPolicyAccess = true)
        val controller = AppOwnedDndController(platform)

        assertTrue(controller.activate())
        assertTrue(controller.deactivate())

        assertEquals(listOf(AppDndFilter.PRIORITY, AppDndFilter.ALL), platform.filters)
    }

    @Test
    fun `旧系统或无权限时不调用可能修改全局 DND 的 API`() {
        val oldPlatform = FakeDndPlatform(sdkInt = 34, hasPolicyAccess = true)
        val deniedPlatform = FakeDndPlatform(sdkInt = 36, hasPolicyAccess = false)

        assertFalse(AppOwnedDndController(oldPlatform).activate())
        assertFalse(AppOwnedDndController(deniedPlatform).activate())
        assertTrue(oldPlatform.filters.isEmpty())
        assertTrue(deniedPlatform.filters.isEmpty())
    }

    private class FakeDndPlatform(
        override val sdkInt: Int,
        override val hasPolicyAccess: Boolean,
    ) : AppDndPlatform {
        val filters = mutableListOf<AppDndFilter>()
        override fun setInterruptionFilter(filter: AppDndFilter) {
            filters += filter
        }
    }
}
