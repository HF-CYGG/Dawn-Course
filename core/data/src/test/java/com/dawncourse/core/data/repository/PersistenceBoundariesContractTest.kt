package com.dawncourse.core.data.repository

import com.dawncourse.core.data.local.entity.SyncSourceBindingEntity
import com.dawncourse.core.data.local.entity.projectForBackupExport
import com.dawncourse.core.data.local.entity.toDomainOrNull
import com.dawncourse.core.domain.model.SyncProviderType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 持久化边界必须隔离未知枚举值与课程业务键约束。 */
class PersistenceBoundariesContractTest {

    @Test
    fun unknownProviderDoesNotThrowAndIsExcludedFromBackupCandidate() {
        val valid = binding(provider = SyncProviderType.WAKEUP.name)
        val invalid = binding(provider = "FUTURE_PROVIDER")

        assertEquals(SyncProviderType.WAKEUP, valid.toDomainOrNull()?.provider)
        assertNull(invalid.toDomainOrNull())
        val projection = listOf(valid, invalid).projectForBackupExport()

        assertEquals(listOf(SyncProviderType.WAKEUP), projection.bindings.map { it.provider })
        assertEquals(1, projection.invalidBindingCount)
    }

    @Test
    fun backupSkipsInvalidBindingsWithoutExportingIdentifiersOrCredentials() {
        val source = source("BackupSnapshotBuilder.kt")

        assertTrue(source.contains("projectForBackupExport"))
        assertTrue(source.contains("无效同步来源绑定"))
        assertTrue(source.contains("课程、学期与课表仍会正常导出"))
        assertTrue(!source.contains("sourceBindingId"))
        assertTrue(!source.contains("credential"))
    }

    @Test
    fun restorePreImageKeepsRawBindingEntitiesUntilValidatedReplacementIsWritten() {
        val source = source("BackupRestoreCoordinator.kt")

        assertTrue(source.contains("sourceBindings = database.syncSourceBindingDao().getAllOnce()"))
        assertTrue(!source.contains("sourceBindings = database.syncSourceBindingDao().getAllOnce().map { it.toDomain() }"))
        assertTrue(source.contains("sourceBindings.forEach { database.syncSourceBindingDao().insert(it) }"))
        assertTrue(source.contains("sourceBindings.forEach { database.syncSourceBindingDao().insert(it.toEntity()) }"))
    }

    @Test
    fun normalCourseInsertsAbortAndAtomicBoundariesMapOnlyConstraintFailures() {
        val dao = source("../local/dao/CourseDao.kt")
        val repository = source("CourseRepositoryImpl.kt")

        assertTrue(dao.contains("@Insert(onConflict = OnConflictStrategy.ABORT)\n    suspend fun insertCourse"))
        assertTrue(dao.contains("@Insert(onConflict = OnConflictStrategy.ABORT)\n    suspend fun insertCourses"))
        assertTrue(
            dao.substringAfter("@Insert(onConflict = OnConflictStrategy.IGNORE)")
                .contains("suspend fun insertCoursesIgnoringDuplicates"),
        )
        assertTrue(repository.contains("catch (failure: SQLiteConstraintException)"))
        assertTrue(repository.contains("存在重复课程或课程已变化，请刷新后重试"))
        assertTrue(!repository.contains("catch (failure: Throwable)"))

        listOf(
            "saveCoursesAtomically",
            "saveCoursesIfScopeActive",
            "restoreCoursesIfScopeActive",
            "undoRescheduleIfScopeActive",
        ).forEach { methodName ->
            val method = repository.substringAfter("override suspend fun $methodName")
                .substringBefore("override suspend fun", missingDelimiterValue = repository)
            assertTrue("$methodName 必须在事务调用外映射约束冲突", method.contains("saveWithConstraintRejection"))
        }
    }

    private fun binding(provider: String) = SyncSourceBindingEntity(
        sourceBindingId = "binding",
        profileId = 1L,
        semesterId = 2L,
        provider = provider,
        createdAt = 3L,
        updatedAt = 4L,
    )

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/dawncourse/core/data/repository/$name"),
            File("core/data/src/main/java/com/dawncourse/core/data/repository/$name"),
        )
        return candidates.firstOrNull(File::exists)?.readText()
            ?: error("找不到源码：$name")
    }
}
