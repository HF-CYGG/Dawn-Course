package com.dawncourse.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StartupSnapshotFactoryTest {

    @Test
    fun `creates minimal signed snapshot from stable live timetable`() {
        val snapshot = createStartupSnapshot(
            activeContext = ActiveTimetableContext(
                profile = TimetableProfile(
                    id = 9L,
                    uuid = "profile-nine",
                    name = "不应进入快照",
                    activeSemesterId = 17L,
                ),
                semester = Semester(
                    id = 17L,
                    profileId = 9L,
                    name = "2026 秋",
                    startDate = 1_780_000_000_000L,
                    weekCount = 18,
                ),
            ),
            courses = listOf(
                Course(
                    id = 2L,
                    semesterId = 17L,
                    name = "高等数学",
                    teacher = "张老师",
                    location = "A101",
                    dayOfWeek = 1,
                    startSection = 1,
                    duration = 2,
                    startWeek = 1,
                    endWeek = 18,
                    weekType = Course.WEEK_TYPE_ODD,
                    color = "#aabbcc",
                    note = "不应进入快照",
                    originId = 99L,
                    isModified = true,
                ),
            ),
            settings = AppSettings(
                dividerColor = "#112233",
                sectionTimes = listOf(SectionTime("08:00", "08:45")),
                enableWebDavAutoSync = true,
                lastImportUrl = "https://not-in-snapshot.example",
            ),
            createdAtEpochMillis = 1_780_000_000_000L,
            zoneId = "Asia/Shanghai",
        )

        assertEquals(9L, snapshot.profile.id)
        assertEquals("profile-nine", snapshot.profile.uuid)
        assertEquals(17L, snapshot.semester?.id)
        assertEquals("高等数学", snapshot.courses.single().name)
        assertEquals(StartupSnapshotWeekType.ODD, snapshot.courses.single().weekType)
        assertEquals("#112233", snapshot.visualSettings.dividerColor)
        assertEquals(StartupSnapshot.TTL_MILLIS, snapshot.expiresAtEpochMillis - snapshot.createdAtEpochMillis)
        assertEquals(StartupSnapshotRevision.create(snapshot), snapshot.revision)
    }

    @Test
    fun `live visual and course changes create independent snapshot revisions`() {
        val context = ActiveTimetableContext(
            profile = TimetableProfile(id = 9L, uuid = "profile-nine", name = "课表", activeSemesterId = 17L),
            semester = Semester(
                id = 17L,
                profileId = 9L,
                name = "2026 秋",
                startDate = 1_780_000_000_000L,
                weekCount = 18,
            ),
        )
        val course = Course(
            id = 2L,
            semesterId = 17L,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 18,
        )
        val baseline = createStartupSnapshot(context, listOf(course), AppSettings(), 1_000L, "Asia/Shanghai")
        val changedVisual = createStartupSnapshot(context, listOf(course), AppSettings(dividerColor = "#000000"), 1_000L, "Asia/Shanghai")
        val changedCourse = createStartupSnapshot(context, listOf(course.copy(name = "线性代数")), AppSettings(), 1_000L, "Asia/Shanghai")

        assertNotEquals(baseline.revision, changedVisual.revision)
        assertNotEquals(baseline.revision, changedCourse.revision)
    }

    @Test
    fun `unknown live week type is not silently rewritten into a snapshot`() {
        val context = ActiveTimetableContext(
            profile = TimetableProfile(id = 9L, uuid = "profile-nine", name = "课表", activeSemesterId = 17L),
            semester = Semester(id = 17L, profileId = 9L, name = "2026 秋", startDate = 1L, weekCount = 18),
        )
        val invalidCourse = Course(
            id = 2L,
            semesterId = 17L,
            name = "高等数学",
            dayOfWeek = 1,
            startSection = 1,
            duration = 2,
            startWeek = 1,
            endWeek = 18,
            weekType = 99,
        )

        assertThrows(IllegalArgumentException::class.java) {
            createStartupSnapshot(context, listOf(invalidCourse), AppSettings(), 1_000L, "Asia/Shanghai")
        }
    }
}
