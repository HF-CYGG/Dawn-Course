package com.dawncourse.core.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawncourse.core.data.local.AppDatabase
import com.dawncourse.core.data.local.entity.SemesterEntity
import com.dawncourse.core.data.local.entity.TimetableProfileEntity
import com.dawncourse.core.domain.model.Course
import com.dawncourse.core.domain.repository.CourseRepository
import java.util.concurrent.CountDownLatch
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseAtomicSaveInstrumentationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CourseRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { insertDefaultProfile() }
        repository = CourseRepositoryImpl(
            database.courseDao(),
            database,
            Provider { error("scope coordinator is not used by these legacy atomic-save tests") },
            OperationalDataMutationGate(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun concurrentSemesterDeleteAndSaveNeverLeavesDanglingCourse() = runBlocking {
        repeat(20) { index ->
            database.clearAllTables()
            insertDefaultProfile()
            val semesterId = database.semesterDao().insertSemester(semester(index + 1L))
            val start = CountDownLatch(1)
            val saving = async(Dispatchers.IO) {
                start.await()
                repository.saveCoursesAtomically(listOf(course(semesterId)), editingCourseId = 0L)
            }
            val deleting = async(Dispatchers.IO) {
                start.await()
                database.semesterDao().deleteSemesterAndCourses(semester(index + 1L).copy(id = semesterId))
            }
            start.countDown()
            saving.await()
            deleting.await()

            val remainingSemester = database.semesterDao().getSemesterById(semesterId)
            val remainingCourses = database.courseDao().getAllCoursesOnce()
            assertTrue(remainingSemester != null || remainingCourses.none { it.semesterId == semesterId })
        }
    }

    @Test
    fun duplicateBusinessKeyReturnsRejectedAndKeepsExistingCourse() = runBlocking {
        val semesterId = database.semesterDao().insertSemester(semester(1L))
        val existing = course(semesterId, name = "重复课程")
        val existingId = database.courseDao().insertCourse(existing.toEntityForTest())

        val result = repository.saveCoursesAtomically(
            courses = listOf(existing.copy(id = 0L, teacher = "不同教师")),
            editingCourseId = 0L,
        )

        assertEquals(
            CourseRepository.AtomicSaveResult.Rejected("存在重复课程或课程已变化，请刷新后重试"),
            result,
        )
        assertEquals(existingId, database.courseDao().getAllCoursesOnce().single().id)
        assertEquals("重复课程", database.courseDao().getCourseById(existingId)?.name)
    }

    @Test
    fun splitEditBusinessKeyConflictReturnsRejectedAndRollsBackOriginalDelete() = runBlocking {
        val semesterId = database.semesterDao().insertSemester(semester(1L))
        val original = course(semesterId, name = "原课程")
        val originalId = database.courseDao().insertCourse(original.toEntityForTest())
        val conflictingSegment = course(semesterId, name = "拆分课程").copy(endWeek = 8)
        val conflictId = database.courseDao().insertCourse(conflictingSegment.toEntityForTest())

        val result = repository.saveCoursesAtomically(
            courses = listOf(
                conflictingSegment,
                conflictingSegment.copy(startWeek = 9, endWeek = 16),
            ),
            editingCourseId = originalId,
        )

        assertNotNull(database.courseDao().getCourseById(originalId))
        assertNotNull(database.courseDao().getCourseById(conflictId))
        assertEquals(
            CourseRepository.AtomicSaveResult.Rejected("存在重复课程或课程已变化，请刷新后重试"),
            result,
        )
    }

    private fun semester(seed: Long) = SemesterEntity(
        id = 0L,
        profileId = 1L,
        name = "学期$seed",
        startDate = seed,
        weekCount = 20,
    )

    private suspend fun insertDefaultProfile() {
        database.timetableProfileDao().insert(
            TimetableProfileEntity(
                id = 1L,
                uuid = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                name = "测试课表",
            ),
        )
    }

    private fun course(semesterId: Long, name: String = "课程") = Course(
        semesterId = semesterId,
        name = name,
        dayOfWeek = 1,
        startSection = 1,
        duration = 2,
        startWeek = 1,
        endWeek = 16
    )

    private fun Course.toEntityForTest() = com.dawncourse.core.data.local.entity.CourseEntity(
        id = id,
        semesterId = semesterId,
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        duration = duration,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        color = color,
        isModified = isModified,
        note = note,
        originId = originId
    )
}
