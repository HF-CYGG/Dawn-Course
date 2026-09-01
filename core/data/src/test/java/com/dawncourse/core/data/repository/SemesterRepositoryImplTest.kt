package com.dawncourse.core.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无 Android 运行时即可验证的选择与凭据路径契约；事务结论留给 Room instrumentation。 */
class SemesterRepositoryImplTest {
    @Test
    fun currentSemesterDelegatesToActiveProfileContextAndDoesNotUseLegacySelection() {
        val source = source("SemesterRepositoryImpl.kt")

        assertTrue(source.contains("profileRepository.observeActiveContext()"))
        assertTrue(source.contains("profileSelectionCoordinator.setCurrentSemester(id)"))
        assertFalse(source.contains("SemesterSelectionCoordinator"))
        assertFalse(source.contains("SettingsRepository"))
    }

    @Test
    fun profileCredentialFileNameIsDeterministicIsolatedAndPathSafe() {
        val first = ProfileCredentialFileNamer.fileName("550e8400-e29b-41d4-a716-446655440000")
        val repeated = ProfileCredentialFileNamer.fileName("550e8400-e29b-41d4-a716-446655440000")
        val second = ProfileCredentialFileNamer.fileName("550e8400-e29b-41d4-a716-446655440001")
        val hostile = ProfileCredentialFileNamer.fileName("../profile/../../secret")

        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertTrue(ProfileCredentialFileNamer.isManagedFile(first))
        assertFalse(hostile.contains("/"))
        assertFalse(hostile.contains("\\"))
        assertEquals(hostile, File(hostile).name)
    }

    private fun source(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/dawncourse/core/data/repository/$fileName"),
            File("core/data/src/main/java/com/dawncourse/core/data/repository/$fileName"),
        )
        return candidates.first { it.isFile }.readText()
    }
}
