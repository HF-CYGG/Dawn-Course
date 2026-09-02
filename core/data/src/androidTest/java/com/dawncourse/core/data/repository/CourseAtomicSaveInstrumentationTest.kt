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
    fun splitEditInsertFailureRollsBackOriginalDelete() = runBlocking {
        val semesterId = database.semesterDao().insertSemester(semester(1L))
        val originalId = database.courseDao().insertCourse(course(semesterId).toEntityForTest())
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_course_insert BEFORE INSERT ON courses " +
                "BEGIN SELECT RAISE(ABORT, 'injected insert failure'); END"
        )

        runCatching {
            repository.saveCoursesAtomically(
                courses = listOf(
                    course(semesterId).copy(startWeek = 1, endWeek = 8),
                    course(semesterId).copy(startWeek = 9, endWeek = 16)
                ),
                editingCourseId = originalId
            )
        }

        assertNotNull(database.courseDao().getCourseById(originalId))
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

    private fun course(semesterId: Long) = Course(
        semesterId = semesterId,
        name = "课程",
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
