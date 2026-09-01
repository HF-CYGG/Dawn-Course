package com.dawncourse.feature.widget

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Widget 必须在读取课程后复核活动 Profile，避免 Profile 切换竞态展示旧课程。 */
class WidgetProfileScopeContractTest {

    @Test
    fun `时间线携带 profile identity 并有界复核上下文`() {
        val source = File("src/main/java/com/dawncourse/feature/widget/WidgetTimelineBuilder.kt").readText()

        assertTrue(source.contains("val profileId: Long?"))
        assertTrue(source.contains("val first = buildOnce(today, now)"))
        assertTrue(source.contains("val second = buildOnce(today, now)"))
        assertTrue(source.contains("isStillCurrent(first.contextKey)"))
        assertTrue(source.contains("displayCourses = emptyList()"))
    }
}
