package com.dawncourse.core.data.local.startup

import org.junit.Assert.assertEquals
import org.junit.Test

/** WebDAV 恢复兼容查找顺序的纯 JVM 锁定。 */
class DatabaseRecoveryWebDavPathPolicyTest {
    @Test
    fun currentV4IsTriedBeforeAllLegacyFilenames() {
        assertEquals(
            listOf(
                "DawnCourseBackup/backup_v4.json",
                "DawnCourseBackup/backup_v3.json",
                "DawnCourseBackup/backup_v2.json",
                "DawnCourseBackup/backup_v1.json"
            ),
            DatabaseRecoveryWebDavPathPolicy.CANDIDATES
        )
    }
}
