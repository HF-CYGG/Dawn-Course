package com.dawncourse.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 历史兼容数据库 schema 的迁移集合。
 *
 * 已发布 tag 均为 v5。v3 与 v4 仅用于兼容预发布开发阶段可能遗留的数据库，
 * v4→v5 仍只保留为 SQL 合约，不能将任一迁移视为真实发布升级路径。
 */
object AppDatabaseMigrations {

    /** 预发布历史兼容 schema v3 直接升级到 v5。 */
    val MIGRATION_3_5 = object : Migration(3, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildCoursesForV5(db)
        }
    }

    /**
     * 非发布历史的 v4 SQL 兼容迁移。
     *
     * 不能使用 ALTER TABLE ... DEFAULT：Room v5 的实体 schema 中这些列没有
     * 默认值。重建表可确保迁移后的实际 DDL 与 v5 schema 完全一致。
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            rebuildCoursesForV5(db)
        }
    }

    /** 已发布 v5 到生产 Profile v6 的无损迁移。 */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_TIMETABLE_PROFILES_V6_SQL)
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_timetable_profiles_uuid` " +
                    "ON `timetable_profiles` (`uuid`)",
            )
            db.execSQL(
                """
                INSERT INTO `timetable_profiles` (
                    `uuid`, `name`, `activeSemesterId`, `lastUsedAt`, `sortOrder`, `archived`
                ) VALUES (
                    lower(hex(randomblob(4))) || '-' ||
                    lower(hex(randomblob(2))) || '-4' ||
                    substr(lower(hex(randomblob(2))), 2) || '-' ||
                    substr('89ab', abs(random()) % 4 + 1, 1) ||
                    substr(lower(hex(randomblob(2))), 2) || '-' ||
                    lower(hex(randomblob(6))),
                    '默认课表',
                    COALESCE(
                        (SELECT MIN(`id`) FROM `semesters` WHERE `isCurrent` = 1),
                        (SELECT MIN(`id`) FROM `semesters`)
                    ),
                    0, 0, 0
                )
                """.trimIndent(),
            )

            db.execSQL("DROP TABLE IF EXISTS `semesters_v6_new`")
            db.execSQL(CREATE_SEMESTERS_V6_SQL)
            db.execSQL(
                """
                INSERT INTO `semesters_v6_new` (`id`, `profileId`, `name`, `startDate`, `weekCount`)
                SELECT
                    `id`,
                    (SELECT `id` FROM `timetable_profiles` ORDER BY `id` LIMIT 1),
                    `name`, `startDate`, `weekCount`
                FROM `semesters`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `semesters`")
            db.execSQL("ALTER TABLE `semesters_v6_new` RENAME TO `semesters`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_semesters_profileId` ON `semesters` (`profileId`)",
            )

            db.execSQL(CREATE_SYNC_SOURCE_BINDINGS_V6_SQL)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_source_bindings_profileId` " +
                    "ON `sync_source_bindings` (`profileId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_source_bindings_semesterId` " +
                    "ON `sync_source_bindings` (`semesterId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_courses_originId` ON `courses` (`originId`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_3_5, MIGRATION_4_5, MIGRATION_5_6)

    private fun rebuildCoursesForV5(db: SupportSQLiteDatabase) {
        val historicalSequence = readSequenceHighWater(db)
        val historicalMaximumId = readMaximumCourseId(db)
        val sequenceHighWater = listOfNotNull(historicalSequence, historicalMaximumId).maxOrNull()

        db.execSQL("DROP TABLE IF EXISTS `courses_v5_new`")
        db.execSQL(CREATE_COURSES_V5_SQL)
        db.execSQL(
            """
            INSERT INTO `courses_v5_new` (
                `id`, `semesterId`, `name`, `teacher`, `location`, `dayOfWeek`,
                `startSection`, `duration`, `startWeek`, `endWeek`, `weekType`, `color`,
                `isModified`, `note`, `originId`
            )
            SELECT
                `id`, `semesterId`, `name`, `teacher`, `location`, `dayOfWeek`,
                `startSection`, `duration`, `startWeek`, `endWeek`, `weekType`, `color`,
                0, '', `id`
            FROM `courses`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `courses`")
        db.execSQL("ALTER TABLE `courses_v5_new` RENAME TO `courses`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_semesterId` ON `courses` (`semesterId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_dayOfWeek` ON `courses` (`dayOfWeek`)")
        sequenceHighWater?.let { restoreSequenceHighWater(db, it) }
    }

    private fun readSequenceHighWater(db: SupportSQLiteDatabase): Long? {
        if (!hasTable(db, SQLITE_SEQUENCE_TABLE)) return null
        return db.query(
            "SELECT `seq` FROM `sqlite_sequence` WHERE `name` = ?",
            arrayOf(COURSES_TABLE),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun readMaximumCourseId(db: SupportSQLiteDatabase): Long? =
        db.query("SELECT MAX(`id`) FROM `courses`").use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun restoreSequenceHighWater(db: SupportSQLiteDatabase, sequenceHighWater: Long) {
        if (!hasTable(db, SQLITE_SEQUENCE_TABLE)) return

        db.execSQL(
            "UPDATE `sqlite_sequence` SET `seq` = ? WHERE `name` = ?",
            arrayOf(sequenceHighWater, COURSES_TABLE),
        )
        db.execSQL(
            """
            INSERT INTO `sqlite_sequence` (`name`, `seq`)
            SELECT ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM `sqlite_sequence` WHERE `name` = ?
            )
            """.trimIndent(),
            arrayOf(COURSES_TABLE, sequenceHighWater, COURSES_TABLE),
        )
    }

    private fun hasTable(db: SupportSQLiteDatabase, tableName: String): Boolean =
        db.query(
            "SELECT 1 FROM `sqlite_master` WHERE `type` = 'table' AND `name` = ?",
            arrayOf(tableName),
        ).use { it.moveToFirst() }

    private const val COURSES_TABLE = "courses"
    private const val SQLITE_SEQUENCE_TABLE = "sqlite_sequence"

    private const val CREATE_COURSES_V5_SQL = """
        CREATE TABLE `courses_v5_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `semesterId` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `teacher` TEXT NOT NULL,
            `location` TEXT NOT NULL,
            `dayOfWeek` INTEGER NOT NULL,
            `startSection` INTEGER NOT NULL,
            `duration` INTEGER NOT NULL,
            `startWeek` INTEGER NOT NULL,
            `endWeek` INTEGER NOT NULL,
            `weekType` INTEGER NOT NULL,
            `color` TEXT NOT NULL,
            `isModified` INTEGER NOT NULL,
            `note` TEXT NOT NULL,
            `originId` INTEGER NOT NULL
        )
    """

    private const val CREATE_TIMETABLE_PROFILES_V6_SQL = """
        CREATE TABLE IF NOT EXISTS `timetable_profiles` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `uuid` TEXT NOT NULL,
            `name` TEXT NOT NULL,
            `activeSemesterId` INTEGER,
            `lastUsedAt` INTEGER NOT NULL,
            `sortOrder` INTEGER NOT NULL,
            `archived` INTEGER NOT NULL
        )
    """

    private const val CREATE_SEMESTERS_V6_SQL = """
        CREATE TABLE `semesters_v6_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `profileId` INTEGER NOT NULL,
            `name` TEXT NOT NULL,
            `startDate` INTEGER NOT NULL,
            `weekCount` INTEGER NOT NULL,
            FOREIGN KEY(`profileId`) REFERENCES `timetable_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
    """

    private const val CREATE_SYNC_SOURCE_BINDINGS_V6_SQL = """
        CREATE TABLE IF NOT EXISTS `sync_source_bindings` (
            `sourceBindingId` TEXT NOT NULL,
            `profileId` INTEGER NOT NULL,
            `semesterId` INTEGER NOT NULL,
            `provider` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `updatedAt` INTEGER NOT NULL,
            PRIMARY KEY(`sourceBindingId`),
            FOREIGN KEY(`profileId`) REFERENCES `timetable_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`semesterId`) REFERENCES `semesters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
    """
}
