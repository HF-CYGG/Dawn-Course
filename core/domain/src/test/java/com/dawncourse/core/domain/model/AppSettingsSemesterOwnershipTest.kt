package com.dawncourse.core.domain.model

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * AppSettings 不得再次承载 Room 学期元数据。
 */
class AppSettingsSemesterOwnershipTest {

    @Test
    fun appSettingsDoesNotExposeDuplicatedSemesterMetadata() {
        val propertyNames = AppSettings::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("学期名称只能来自 Room", "currentSemesterName" in propertyNames)
        assertFalse("总周数只能来自 Room", "totalWeeks" in propertyNames)
        assertFalse("开学时间只能来自 Room", "startDateTimestamp" in propertyNames)
    }
}
