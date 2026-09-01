package com.dawncourse.core.domain.model

import com.dawncourse.core.domain.repository.ImportCommitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入提交必须把目标 Profile 与学期范围表达为不可歧义的领域契约。 */
class ImportCommitContractTest {

    @Test
    fun destinationsKeepProfileAndSemesterScopeExplicit() {
        assertEquals(7L, ImportDestination.NewSemester(profileId = 7L).profileId)
        assertEquals(ImportDestination.NewProfile, ImportDestination.NewProfile)

        val overwrite = ImportDestination.OverwriteSemester(profileId = 7L, semesterId = 11L)
        assertEquals(7L, overwrite.profileId)
        assertEquals(11L, overwrite.semesterId)
    }

    @Test
    fun commitRequestCarriesImmutableCapturedDestinationAndCourses() {
        val request = ImportCommitRequest(
            destination = ImportDestination.NewSemester(profileId = 7L),
            semester = NewSemesterSpec(name = "2026 秋", startDate = 1L, weekCount = 20),
            courses = listOf(
                Course(
                    name = "高等数学",
                    dayOfWeek = 1,
                    startSection = 1,
                    duration = 2,
                    startWeek = 1,
                    endWeek = 16,
                ),
            ),
        )

        assertEquals(7L, (request.destination as ImportDestination.NewSemester).profileId)
        assertEquals(1, request.courses.size)
    }

    @Test
    fun repositoryExposesPreviewAndAtomicCommit() {
        val methods = ImportCommitRepository::class.java.methods.map { it.name }

        // Kotlin Result 返回值会对 JVM 方法名做稳定 mangle；测试只校验领域 API 仍同时暴露预览与提交。
        assertTrue(methods.any { it.startsWith("preview") })
        assertTrue(methods.any { it.startsWith("commit") })
    }

    @Test
    fun inconsistentResultCannotBeMistakenForRejectedBusinessInput() {
        val result: ImportCommitResult = ImportCommitResult.Inconsistent("补偿失败")

        assertTrue(result is ImportCommitResult.Inconsistent)
        assertTrue(result !is ImportCommitResult.Rejected)
    }
}
