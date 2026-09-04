package com.dawncourse.core.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        APP_DATABASE_CLASS_NAME,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun deleteStaleTestDatabases() {
        deleteTestDatabases()
    }

    @After
    fun deleteCreatedTestDatabases() {
        deleteTestDatabases()
    }

    @Test
    fun migrate3To5_preservesCourseDataAndSequenceHighWater() {
        createLegacyHelper(
            databaseName = TEST_DATABASE,
            coursesCreateSql = COURSES_V4_CREATE_SQL,
            version = 3,
        ).use { fixtureHelper ->
            fixtureHelper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO semesters (id, name, startDate, weekCount, isCurrent)
                VALUES (7, '2026 春季', 1700000000000, 20, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek,
                    startSection, duration, startWeek, endWeek, weekType, color
                ) VALUES (42, 7, '高等数学', '张老师', 'A101', 1, 1, 2, 1, 16, 0, '#FFFFFF')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek,
                    startSection, duration, startWeek, endWeek, weekType, color
                ) VALUES ($HISTORICAL_SEQUENCE_HIGH_WATER, 7, '旧高水位', '张老师', 'A101', 1, 1, 2, 1, 16, 0, '#000000')
                """.trimIndent(),
            )
            execSQL("DELETE FROM courses WHERE id = $HISTORICAL_SEQUENCE_HIGH_WATER")
            }
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            5,
            true,
            AppDatabaseMigrations.MIGRATION_3_5,
        ).apply {
            query("SELECT * FROM courses WHERE id = 42").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("semesterId")))
                assertEquals("高等数学", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertEquals("张老师", cursor.getString(cursor.getColumnIndexOrThrow("teacher")))
                assertEquals("A101", cursor.getString(cursor.getColumnIndexOrThrow("location")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("dayOfWeek")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("startSection")))
                assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("duration")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("startWeek")))
                assertEquals(16, cursor.getInt(cursor.getColumnIndexOrThrow("endWeek")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("weekType")))
                assertEquals("#FFFFFF", cursor.getString(cursor.getColumnIndexOrThrow("color")))
                assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("originId")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isModified")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("note")))
            }
            query("SELECT name, startDate, weekCount, isCurrent FROM semesters WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026 春季", cursor.getString(0))
                assertEquals(1_700_000_000_000L, cursor.getLong(1))
                assertEquals(20, cursor.getInt(2))
                assertEquals(1, cursor.getInt(3))
            }
            insertV5CourseWithoutId(this, "迁移后新增")
            query("SELECT id FROM courses WHERE name = '迁移后新增'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getLong(0) > HISTORICAL_SEQUENCE_HIGH_WATER)
            }
            close()
        }
    }

    @Test
    fun migrate4To6_compatibilityFixtureOpensWithRoomAndPreservesSequence() {
        val fixtureHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
                .name(COMPATIBILITY_DATABASE)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(COURSES_V4_CREATE_SQL)
                        db.execSQL(SEMESTERS_CREATE_SQL)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        fixtureHelper.writableDatabase.apply {
            execSQL("CREATE TABLE courses_v5_new (discardedValue TEXT)")
            execSQL(
                """
                INSERT INTO semesters (id, name, startDate, weekCount, isCurrent)
                VALUES (7, '2026 秋季', 1800000000000, 18, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek,
                    startSection, duration, startWeek, endWeek, weekType, color
                ) VALUES (99, 7, '离散数学', '李老师', 'B202', 2, 3, 2, 1, 16, 1, '#123456')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek,
                    startSection, duration, startWeek, endWeek, weekType, color
                ) VALUES ($HISTORICAL_SEQUENCE_HIGH_WATER, 7, '旧高水位', '李老师', 'B202', 2, 3, 2, 1, 16, 1, '#000000')
                """.trimIndent(),
            )
            execSQL("DELETE FROM courses WHERE id = $HISTORICAL_SEQUENCE_HIGH_WATER")
        }
        fixtureHelper.close()

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            appDatabaseClass(),
            COMPATIBILITY_DATABASE,
        )
            .addMigrations(*AppDatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        database.openHelper.writableDatabase.apply {
            query("SELECT * FROM courses WHERE id = 99").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(99L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                assertEquals(7L, cursor.getLong(cursor.getColumnIndexOrThrow("semesterId")))
                assertEquals("离散数学", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertEquals("李老师", cursor.getString(cursor.getColumnIndexOrThrow("teacher")))
                assertEquals("B202", cursor.getString(cursor.getColumnIndexOrThrow("location")))
                assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("dayOfWeek")))
                assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("startSection")))
                assertEquals(2, cursor.getInt(cursor.getColumnIndexOrThrow("duration")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("startWeek")))
                assertEquals(16, cursor.getInt(cursor.getColumnIndexOrThrow("endWeek")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("weekType")))
                assertEquals("#123456", cursor.getString(cursor.getColumnIndexOrThrow("color")))
                assertEquals(99L, cursor.getLong(cursor.getColumnIndexOrThrow("originId")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isModified")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("note")))
            }
            query("SELECT name, startDate, weekCount, profileId FROM semesters WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026 秋季", cursor.getString(0))
                assertEquals(1_800_000_000_000L, cursor.getLong(1))
                assertEquals(18, cursor.getInt(2))
                assertTrue(cursor.getLong(3) > 0L)
            }
            query("PRAGMA table_info(courses)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) in V5_ADJUSTMENT_COLUMNS) {
                        assertNull(cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                    }
                }
            }
            query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
            query("SELECT name FROM sqlite_master WHERE type = 'index' AND name IN ('index_courses_semesterId', 'index_courses_dayOfWeek', 'index_courses_originId')").use { cursor ->
                assertEquals(3, cursor.count)
            }
            query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_courses_dedupe'").use { cursor ->
                assertEquals(1, cursor.count)
            }
            query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'courses_v5_new'").use { cursor ->
                assertFalse(cursor.moveToFirst())
            }
            insertV5CourseWithoutId(this, "兼容迁移后新增")
            query("SELECT id FROM courses WHERE name = '兼容迁移后新增'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getLong(0) > HISTORICAL_SEQUENCE_HIGH_WATER)
            }
        }
        database.close()
    }

    @Test
    fun migrate4To5_handlesMissingAndEmptySqliteSequence() {
        createLegacyHelper(
            MISSING_SEQUENCE_DATABASE,
            COURSES_WITHOUT_AUTOINCREMENT_CREATE_SQL,
        ).use { helper ->
            helper.writableDatabase.apply {
                execSQL(
                    """
                    INSERT INTO courses (
                        id, semesterId, name, teacher, location, dayOfWeek,
                        startSection, duration, startWeek, endWeek, weekType, color
                    ) VALUES (12, 7, '无序列表课程', '测试老师', '测试教室', 1, 1, 1, 1, 16, 0, '#FFFFFF')
                    """.trimIndent(),
                )
                AppDatabaseMigrations.MIGRATION_4_5.migrate(this)
                insertV5CourseWithoutId(this, "无序列表迁移后新增")
                query("SELECT id FROM courses WHERE name = '无序列表迁移后新增'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getLong(0) > 12L)
                }
            }
        }

        createLegacyHelper(
            EMPTY_SEQUENCE_DATABASE,
            COURSES_V4_CREATE_SQL,
        ).use { helper ->
            helper.writableDatabase.apply {
                AppDatabaseMigrations.MIGRATION_4_5.migrate(this)
                insertV5CourseWithoutId(this, "空序列表迁移后新增")
                query("SELECT id FROM courses WHERE name = '空序列表迁移后新增'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
            }
        }
    }

    @Test
    fun publishedV5DatabaseUpgradesToProfileV6WithoutLosingOperationalData() {
        helper.createDatabase(PROFILE_V6_DATABASE, 5).apply {
            execSQL(
                """
                INSERT INTO semesters (id, name, startDate, weekCount, isCurrent)
                VALUES
                    (7, '2026 春季', 1700000000000, 20, 0),
                    (9, '2026 秋季', 1800000000000, 18, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek, startSection, duration,
                    startWeek, endWeek, weekType, color, isModified, note, originId
                ) VALUES (42, 9, '数据库系统', '测试教师', 'C301', 3, 5, 2, 1, 16, 0, '#123456', 1, '旧调课', 12)
                """.trimIndent(),
            )
            close()
        }

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            appDatabaseClass(),
            PROFILE_V6_DATABASE,
        )
            .addMigrations(*AppDatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        database.openHelper.writableDatabase.apply {
            query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
            query("SELECT id, activeSemesterId FROM timetable_profiles").use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(9L, cursor.getLong(1))
            }
            val profileId = query("SELECT id FROM timetable_profiles LIMIT 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0)
            }
            query("SELECT id, profileId FROM semesters ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(profileId, cursor.getLong(1))
                assertTrue(cursor.moveToNext())
                assertEquals(9L, cursor.getLong(0))
                assertEquals(profileId, cursor.getLong(1))
            }
            query("SELECT semesterId, originId, isModified, note FROM courses WHERE id = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(9L, cursor.getLong(0))
                assertEquals(12L, cursor.getLong(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals("旧调课", cursor.getString(3))
            }
        }
        database.close()
    }

    @Test
    fun migrate6To7_dedupesCoursesAndKeepsAdjustmentRecordsAndUniqueIndex() {
        helper.createDatabase(DEDUPE_V7_DATABASE, 6).apply {
            execSQL(
                """
                INSERT INTO timetable_profiles (id, uuid, name, activeSemesterId, lastUsedAt, sortOrder, archived)
                VALUES (1, 'uuid-1', '默认课表', 5, 0, 0, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO semesters (id, profileId, name, startDate, weekCount)
                VALUES (5, 1, '2026 春季', 1700000000000, 20)
                """.trimIndent(),
            )
            // 成对重复：业务键完全一致，仅 id 不同 —— 迁移后只应保留 MIN(id) = 10。
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek, startSection, duration,
                    startWeek, endWeek, weekType, color, isModified, note, originId
                ) VALUES
                    (10, 5, '传感器与检测技术', '张磊', 'A101', 2, 7, 2, 1, 16, 0, '#111111', 0, '', 0),
                    (11, 5, '传感器与检测技术', '张磊 教学班:X 学分:3.0', 'A101', 2, 7, 2, 1, 16, 0, '#222222', 0, '', 0)
                """.trimIndent(),
            )
            // 调课拆分记录：同一时段但 originId / isModified 不同 —— 不能被误删。
            execSQL(
                """
                INSERT INTO courses (
                    id, semesterId, name, teacher, location, dayOfWeek, startSection, duration,
                    startWeek, endWeek, weekType, color, isModified, note, originId
                ) VALUES (12, 5, '传感器与检测技术', '张磊', 'A101', 2, 7, 2, 1, 16, 0, '#333333', 1, '调课', 10)
                """.trimIndent(),
            )
            close()
        }

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            appDatabaseClass(),
            DEDUPE_V7_DATABASE,
        )
            .addMigrations(*AppDatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        database.openHelper.writableDatabase.apply {
            query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
            }
            query("SELECT id FROM courses ORDER BY id").use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(10L, cursor.getLong(0))
                assertTrue(cursor.moveToNext())
                assertEquals(12L, cursor.getLong(0))
            }
            query("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'index_courses_dedupe'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).contains("UNIQUE"))
            }
            // 再插入同业务键的一行应被唯一索引忽略（INSERT OR IGNORE）。
            execSQL(
                """
                INSERT OR IGNORE INTO courses (
                    semesterId, name, teacher, location, dayOfWeek, startSection, duration,
                    startWeek, endWeek, weekType, color, isModified, note, originId
                ) VALUES (5, '传感器与检测技术', '别的老师', 'B202', 2, 7, 2, 1, 16, 0, '#444444', 0, '', 0)
                """.trimIndent(),
            )
            query("SELECT COUNT(*) FROM courses").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
        database.close()
    }

    private fun insertV5CourseWithoutId(db: SupportSQLiteDatabase, name: String) {
        db.execSQL(
            """
            INSERT INTO courses (
                semesterId, name, teacher, location, dayOfWeek, startSection, duration,
                startWeek, endWeek, weekType, color, isModified, note, originId
            ) VALUES (7, '$name', '测试老师', '测试教室', 1, 1, 1, 1, 16, 0, '#FFFFFF', 0, '', 0)
            """.trimIndent(),
        )
    }

    private fun createLegacyHelper(
        databaseName: String,
        coursesCreateSql: String,
        version: Int = 4,
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(coursesCreateSql)
                    db.execSQL(SEMESTERS_CREATE_SQL)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build(),
    )

    private fun deleteTestDatabases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TEST_DATABASE_NAMES.forEach(context::deleteDatabase)
    }

    @Suppress("UNCHECKED_CAST")
    private fun appDatabaseClass(): Class<RoomDatabase> =
        Class.forName(APP_DATABASE_CLASS_NAME) as Class<RoomDatabase>

    private companion object {
        const val TEST_DATABASE = "app-database-migration-test"
        const val COMPATIBILITY_DATABASE = "app-database-v4-compatibility-test"
        const val MISSING_SEQUENCE_DATABASE = "app-database-missing-sequence-test"
        const val EMPTY_SEQUENCE_DATABASE = "app-database-empty-sequence-test"
        const val PROFILE_V6_DATABASE = "app-database-profile-v6-test"
        const val DEDUPE_V7_DATABASE = "app-database-dedupe-v7-test"
        val TEST_DATABASE_NAMES = listOf(
            TEST_DATABASE,
            COMPATIBILITY_DATABASE,
            MISSING_SEQUENCE_DATABASE,
            EMPTY_SEQUENCE_DATABASE,
            PROFILE_V6_DATABASE,
            DEDUPE_V7_DATABASE,
        )
        const val APP_DATABASE_CLASS_NAME = "com.dawncourse.core.data.local.AppDatabase"
        const val HISTORICAL_SEQUENCE_HIGH_WATER = 1_000L
        val V5_ADJUSTMENT_COLUMNS = setOf("originId", "isModified", "note")
        const val COURSES_V4_CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS courses (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                semesterId INTEGER NOT NULL,
                name TEXT NOT NULL,
                teacher TEXT NOT NULL,
                location TEXT NOT NULL,
                dayOfWeek INTEGER NOT NULL,
                startSection INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                startWeek INTEGER NOT NULL,
                endWeek INTEGER NOT NULL,
                weekType INTEGER NOT NULL,
                color TEXT NOT NULL
            )
        """
        const val COURSES_WITHOUT_AUTOINCREMENT_CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS courses (
                id INTEGER PRIMARY KEY NOT NULL,
                semesterId INTEGER NOT NULL,
                name TEXT NOT NULL,
                teacher TEXT NOT NULL,
                location TEXT NOT NULL,
                dayOfWeek INTEGER NOT NULL,
                startSection INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                startWeek INTEGER NOT NULL,
                endWeek INTEGER NOT NULL,
                weekType INTEGER NOT NULL,
                color TEXT NOT NULL
            )
        """
        const val SEMESTERS_CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS semesters (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                startDate INTEGER NOT NULL,
                weekCount INTEGER NOT NULL,
                isCurrent INTEGER NOT NULL
            )
        """
    }
}
