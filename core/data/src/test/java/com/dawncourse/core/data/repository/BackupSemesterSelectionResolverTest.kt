package com.dawncourse.core.data.repository

import com.dawncourse.core.domain.model.AppSettings
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.model.LocalBackupData
import com.dawncourse.core.domain.model.Semester
import com.dawncourse.core.domain.model.WebDavBackup
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 备份格式兼容与当前学期恢复策略测试。
 */
class BackupSemesterSelectionResolverTest {
    private val gson = Gson()

    @Test
    fun legacyLocalV1WithoutSelectionStillParsesAndUsesSmallestLegacyCurrentId() {
        val backup = gson.fromJson(localV1Json(), LocalBackupData::class.java)

        val selected = BackupSemesterSelectionResolver.resolve(
            version = backup.version,
            requestedSemesterId = backup.selectedSemesterId,
            semesters = backup.semesters
        )

        assertEquals(1, backup.version)
        assertNull(backup.selectedSemesterId)
        assertEquals(2L, selected)
    }

    @Test
    fun localV2ValidSelectionTakesPriorityOverLegacyFlags() {
        val backup = LocalBackupData(
            version = 2,
            exportTime = 1L,
            appVersionName = "test",
            settings = AppSettings(),
            semesters = listOf(
                semester(id = 2L, isCurrent = true),
                semester(id = 9L, isCurrent = false)
            ),
            courses = emptyList(),
            selectedSemesterId = 9L
        )

        val restored = gson.fromJson(gson.toJson(backup), LocalBackupData::class.java)

        assertEquals(4, LocalBackupData.CURRENT_VERSION)
        assertEquals(2, restored.version)
        assertEquals(9L, restored.selectedSemesterId)
        assertEquals(
            9L,
            BackupSemesterSelectionResolver.resolve(
                version = restored.version,
                requestedSemesterId = restored.selectedSemesterId,
                semesters = restored.semesters
            )
        )
    }

    @Test
    fun v2NoSelectionOrInvalidSelectionNeverReactivatesLegacyCurrent() {
        val semesters = listOf(
            semester(id = 7L, isCurrent = true),
            semester(id = 3L, isCurrent = true)
        )

        assertNull(BackupSemesterSelectionResolver.resolve(2, 0L, semesters))
        assertNull(BackupSemesterSelectionResolver.resolve(2, null, semesters))
        assertNull(BackupSemesterSelectionResolver.resolve(2, 99L, semesters))
    }

    @Test
    fun legacyWebDavV1WithoutSelectionStillParses() {
        val json = """
            {
              "version": 1,
              "lastModified": 1,
              "settings": {},
              "semesters": [
                {"id":4,"name":"旧学期","startDate":1,"weekCount":16,"isCurrent":true}
              ],
              "courses": []
            }
        """.trimIndent()

        val backup = gson.fromJson(json, WebDavBackup::class.java)

        assertEquals(1, backup.version)
        assertNull(backup.selectedSemesterId)
        assertEquals(
            4L,
            BackupSemesterSelectionResolver.resolve(
                version = backup.version,
                requestedSemesterId = backup.selectedSemesterId,
                semesters = backup.semesters
            )
        )
    }

    @Test
    fun webDavV2RoundTripKeepsSelection() {
        val original = WebDavBackup(
            version = 2,
            lastModified = 1L,
            settings = AppSettings(),
            semesters = listOf(semester(id = 11L, isCurrent = false)),
            courses = emptyList<Course>(),
            selectedSemesterId = 11L
        )

        val restored = gson.fromJson(gson.toJson(original), WebDavBackup::class.java)

        assertEquals(2, restored.version)
        assertEquals(11L, restored.selectedSemesterId)
    }

    @Test
    fun v2ExportWritesExplicitZeroForNoSelection() {
        val backup = WebDavBackup(
            version = 2,
            lastModified = 1L,
            settings = AppSettings(),
            semesters = listOf(semester(id = 11L, isCurrent = false)),
            courses = emptyList(),
            selectedSemesterId = 0L
        )

        val json = gson.toJson(backup)

        assertEquals(true, json.contains("\"selectedSemesterId\":0"))
    }

    private fun semester(id: Long, isCurrent: Boolean): Semester = Semester(
        id = id,
        profileId = 1L,
        name = "学期$id",
        startDate = id,
        weekCount = 20,
        isCurrent = isCurrent
    )

    private fun localV1Json(): String = """
        {
          "version": 1,
          "exportTime": 1,
          "appVersionName": "legacy",
          "settings": {
            "currentSemesterName": "旧缓存",
            "totalWeeks": 99,
            "startDateTimestamp": 999
          },
          "semesters": [
            {"id":8,"name":"旧学期8","startDate":8,"weekCount":18,"isCurrent":true},
            {"id":2,"name":"旧学期2","startDate":2,"weekCount":16,"isCurrent":true}
          ],
          "courses": []
        }
    """.trimIndent()
}
