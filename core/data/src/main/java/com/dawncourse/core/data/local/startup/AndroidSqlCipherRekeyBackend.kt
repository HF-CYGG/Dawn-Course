package com.dawncourse.core.data.local.startup

import android.database.Cursor
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase

/** SQLCipher 原生 rekey 实现；不经过明文 ATTACH/sqlcipher_export 后端。 */
class AndroidSqlCipherRekeyBackend : SqlCipherRekeyBackend {
    override fun checkpointAndCloseLegacy(
        database: File,
        legacy: DatabaseKeyMaterial.LegacyPassphrase
    ) {
        require(database.isFile) { "legacy 数据库不存在" }
        withSqlCipherKeyCopy(legacy) { key ->
            SQLiteDatabase.openDatabase(
                database.path,
                key,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            ).use { opened ->
                opened.rawQuery(WAL_CHECKPOINT_SQL, emptyArray<String>()).use { cursor ->
                    require(cursor.moveToFirst()) { "legacy WAL checkpoint 未返回结果" }
                    require(cursor.getInt(0) == 0) { "legacy WAL checkpoint 未完成" }
                }
                opened.rawQuery(JOURNAL_MODE_DELETE_SQL, emptyArray<String>()).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true)) {
                        "legacy 数据库无法切换到 DELETE journal"
                    }
                }
            }
        }
        requireNoHotSidecars(database)
    }

    override fun rekeyCopyAndVerify(
        legacyDatabase: File,
        rawDatabase: File,
        legacy: DatabaseKeyMaterial.LegacyPassphrase,
        raw: DatabaseKeyMaterial.RawKeyLiteral
    ) {
        require(legacyDatabase.isFile) { "legacy pre-image 不存在" }
        require(rawDatabase.isFile) { "raw 工作副本不存在" }
        val source = inspectEncrypted(legacyDatabase, legacy)
        require(source.integrityOk && source.cipherIntegrityOk == true) { "legacy pre-image 校验失败" }

        withSqlCipherKeyCopy(legacy) { legacyKey ->
            SQLiteDatabase.openDatabase(
                rawDatabase.path,
                legacyKey,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            ).use { opened ->
                raw.useSqlCipherBytes { rawLiteral ->
                    // SQLCipher Android 的 changePassword(byte[]) 直接进入 sqlite3_rekey；这里传入
                    // x'<64 hex>'，因此不会再次执行 passphrase KDF。
                    opened.changePassword(rawLiteral)
                }
            }
        }
        requireNoHotSidecars(rawDatabase)
        val rekeyed = inspectEncrypted(rawDatabase, raw)
        require(
            rekeyed.integrityOk &&
                rekeyed.cipherIntegrityOk == true &&
                rekeyed.snapshot == source.snapshot
        ) { "raw rekey 后逻辑或完整性校验不一致" }
    }

    private fun inspectEncrypted(
        database: File,
        keyMaterial: DatabaseKeyMaterial
    ): DatabaseMigrationVerification = withSqlCipherKeyCopy(keyMaterial) { key ->
        SQLiteDatabase.openDatabase(
            database.path,
            key,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null
        ).use { opened ->
            val cipherStatus = readFirstColumn(opened.rawQuery(CIPHER_STATUS_SQL, emptyArray<String>()))
            val cipherErrors = readFirstColumn(
                opened.rawQuery(CIPHER_INTEGRITY_CHECK_SQL, emptyArray<String>())
            )
            DatabaseMigrationVerification(
                snapshot = collectSnapshot(opened),
                integrityOk = readFirstColumn(
                    opened.rawQuery(INTEGRITY_CHECK_SQL, emptyArray<String>())
                ) == listOf("ok"),
                cipherIntegrityOk = cipherStatus == listOf("1") && cipherErrors.isEmpty()
            )
        }
    }

    private fun collectSnapshot(database: SQLiteDatabase): DatabaseMigrationSnapshot {
        val schema = buildList {
            database.rawQuery(SCHEMA_SQL, emptyArray<String>()).use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        DatabaseSchemaIdentity(
                            type = cursor.getString(0),
                            name = cursor.getString(1),
                            tableName = cursor.getString(2),
                            sql = cursor.getString(3).trim()
                        )
                    )
                }
            }
        }
        val tableNames = buildList {
            database.rawQuery(USER_TABLES_SQL, emptyArray<String>()).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        val rowCounts = linkedMapOf<String, Long>()
        tableNames.forEach { tableName ->
            database.rawQuery("SELECT COUNT(*) FROM ${quoteIdentifier(tableName)}", emptyArray<String>())
                .use { cursor ->
                    require(cursor.moveToFirst()) { "无法读取用户表行数" }
                    rowCounts[tableName] = cursor.getLong(0)
                }
        }
        return DatabaseMigrationSnapshot(
            userVersion = database.version,
            autoVacuum = readSingleInt(database.rawQuery(AUTO_VACUUM_SQL, emptyArray<String>())),
            schema = schema,
            userTableRowCounts = rowCounts
        )
    }

    private fun readFirstColumn(cursor: Cursor): List<String> = cursor.use {
        buildList {
            while (it.moveToNext()) add(it.getString(0))
        }
    }

    private fun readSingleInt(cursor: Cursor): Int = cursor.use {
        require(it.moveToFirst()) { "数据库 PRAGMA 未返回结果" }
        val value = it.getInt(0)
        require(!it.moveToNext()) { "数据库 PRAGMA 返回多行" }
        value
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

    private fun requireNoHotSidecars(database: File) {
        listOf("-wal", "-journal").forEach { suffix ->
            val sidecar = File(database.path + suffix)
            require(!sidecar.exists() || sidecar.length() == 0L) { "数据库存在未合并 sidecar" }
        }
    }

    private fun <T> withSqlCipherKeyCopy(
        material: DatabaseKeyMaterial,
        block: (ByteArray) -> T
    ): T = material.useSqlCipherBytes { managed ->
        val copy = managed.copyOf()
        try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    private companion object {
        const val WAL_CHECKPOINT_SQL = "PRAGMA wal_checkpoint(FULL)"
        const val JOURNAL_MODE_DELETE_SQL = "PRAGMA journal_mode=DELETE"
        const val INTEGRITY_CHECK_SQL = "PRAGMA integrity_check"
        const val CIPHER_INTEGRITY_CHECK_SQL = "PRAGMA cipher_integrity_check"
        const val CIPHER_STATUS_SQL = "PRAGMA cipher_status"
        const val AUTO_VACUUM_SQL = "PRAGMA auto_vacuum"
        const val SCHEMA_SQL =
            "SELECT type, name, tbl_name, sql FROM sqlite_master " +
                "WHERE sql IS NOT NULL ORDER BY type, name, tbl_name"
        const val USER_TABLES_SQL =
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' ORDER BY name"
    }
}
