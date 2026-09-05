package com.dawncourse.core.data.local.startup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase

/**
 * 使用 Android SQLite 与 SQLCipher 4.18.0 官方 API 执行明文到加密库的真实导出。
 *
 * 加密 temp 作为 main 打开，明文 pre-image 通过绑定参数 ATTACH 且显式空 KEY；口令只传入
 * SQLCipher byte[] API，不拼入 SQL、路径或日志。`sqlcipher_export('main', 'plaintext')` 使用
 * 官方双参数形式从 attached 明文库复制到已加密 main。
 */
class AndroidPlaintextToSqlCipherMigrationBackend : PlaintextToSqlCipherMigrationBackend {
    init {
        SqlCipherNativeLoader.ensureLoaded()
    }

    /** 合并明文 WAL、切换为 DELETE journal 并关闭连接，拒绝仍含未合并数据的 sidecar。 */
    override fun checkpointAndClosePlaintext(database: File) {
        require(database.isFile) { "待迁移明文数据库不存在" }
        PlatformSQLiteDatabase.openDatabase(
            database.path,
            null,
            PlatformSQLiteDatabase.OPEN_READWRITE
        ).use { opened ->
            opened.rawQuery(WAL_CHECKPOINT_SQL, null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == 0) { "明文 WAL checkpoint 未完成" }
            }
            opened.rawQuery(JOURNAL_MODE_DELETE_SQL, null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true)) {
                    "明文数据库无法切换到 DELETE journal"
                }
            }
        }
        requireNoHotSidecars(database)
    }

    /** 使用平台 SQLite 独立重开 pre-image 并生成稳定快照。 */
    override fun inspectPlaintext(database: File): DatabaseMigrationVerification {
        require(hasPlaintextSqliteHeader(database)) { "pre-image 不是可识别的明文 SQLite" }
        return PlatformSQLiteDatabase.openDatabase(
            database.path,
            null,
            PlatformSQLiteDatabase.OPEN_READONLY
        ).use { opened ->
            DatabaseMigrationVerification(
                snapshot = collectSnapshot(opened.version) { sql -> opened.rawQuery(sql, null) },
                integrityOk = readFirstColumn(opened.rawQuery(INTEGRITY_CHECK_SQL, null)) == listOf("ok"),
                cipherIntegrityOk = null
            )
        }
    }

    /** 创建新加密 main、绑定 ATTACH 明文路径、执行官方 export，并关闭重开后返回完整校验。 */
    override fun exportPlaintextToEncrypted(
        plaintextDatabase: File,
        encryptedDatabase: File,
        keyMaterial: DatabaseKeyMaterial,
        sourceSnapshot: DatabaseMigrationSnapshot
    ): DatabaseMigrationVerification {
        require(plaintextDatabase.isFile) { "明文 pre-image 不存在" }
        require(!encryptedDatabase.exists()) { "加密 temp 已存在" }
        require(plaintextDatabase.parentFile == encryptedDatabase.parentFile) { "加密 temp 必须与明文库同目录" }
        withSqlCipherKeyCopy(keyMaterial) { workingPassphrase ->
            CipherSQLiteDatabase.openOrCreateDatabase(
                encryptedDatabase,
                workingPassphrase,
                null,
                null
            ).use { target ->
                var attached = false
                try {
                    require(sourceSnapshot.autoVacuum in AUTO_VACUUM_NONE..AUTO_VACUUM_INCREMENTAL) {
                        "明文数据库 auto_vacuum 模式无效"
                    }
                    // sqlcipher_export 不复制 auto_vacuum；目标仍为空时必须先显式保持源值。
                    target.execSQL("PRAGMA auto_vacuum = ${sourceSnapshot.autoVacuum}")
                    target.rawQuery(AUTO_VACUUM_SQL, emptyArray<String>()).use { cursor ->
                        require(cursor.moveToFirst() && cursor.getInt(0) == sourceSnapshot.autoVacuum) {
                            "加密目标无法保持 auto_vacuum"
                        }
                    }
                    target.execSQL(ATTACH_PLAINTEXT_SQL, arrayOf<Any>(plaintextDatabase.absolutePath))
                    attached = true
                    target.rawQuery(EXPORT_SQL, emptyArray<String>()).use { cursor ->
                        require(cursor.moveToFirst()) { "sqlcipher_export 未返回完成结果" }
                    }
                    target.version = sourceSnapshot.userVersion
                } finally {
                    if (attached) {
                        runCatching { target.execSQL(DETACH_PLAINTEXT_SQL) }
                    }
                }
            }
        }
        requireNoHotSidecars(encryptedDatabase)
        return inspectEncrypted(encryptedDatabase, keyMaterial)
    }

    /** 使用同一口令重开加密文件，验证 cipher_status、两类完整性和逻辑快照。 */
    override fun inspectEncrypted(
        database: File,
        keyMaterial: DatabaseKeyMaterial
    ): DatabaseMigrationVerification = withSqlCipherKeyCopy(keyMaterial) { workingPassphrase ->
        CipherSQLiteDatabase.openDatabase(
            database.path,
            workingPassphrase,
            null,
            CipherSQLiteDatabase.OPEN_READONLY,
            null
        ).use { opened ->
            val cipherStatus = readFirstColumn(opened.rawQuery(CIPHER_STATUS_SQL, emptyArray<String>()))
            val cipherErrors = readFirstColumn(
                opened.rawQuery(CIPHER_INTEGRITY_CHECK_SQL, emptyArray<String>())
            )
            DatabaseMigrationVerification(
                snapshot = collectSnapshot(opened.version) { sql ->
                    opened.rawQuery(sql, emptyArray<String>())
                },
                integrityOk = readFirstColumn(
                    opened.rawQuery(INTEGRITY_CHECK_SQL, emptyArray<String>())
                ) == listOf("ok"),
                cipherIntegrityOk = cipherStatus == listOf("1") && cipherErrors.isEmpty()
            )
        }
    }

    /** 读取 sqlite_master、所有用户表行数和 user_version，结果按固定顺序排列。 */
    private fun collectSnapshot(
        userVersion: Int,
        query: (String) -> Cursor
    ): DatabaseMigrationSnapshot {
        val schema = buildList {
            query(SCHEMA_SQL).use { cursor ->
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
        val rowCounts = linkedMapOf<String, Long>()
        val tableNames = buildList {
            query(USER_TABLES_SQL).use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        tableNames.forEach { tableName ->
            val quotedTable = quoteIdentifier(tableName)
            query("SELECT COUNT(*) FROM $quotedTable").use { cursor ->
                require(cursor.moveToFirst()) { "无法读取用户表行数" }
                rowCounts[tableName] = cursor.getLong(0)
            }
        }
        return DatabaseMigrationSnapshot(
            userVersion = userVersion,
            autoVacuum = readSingleInt(query(AUTO_VACUUM_SQL)),
            schema = schema,
            userTableRowCounts = rowCounts
        )
    }

    /** 读取单列结果；cipher_integrity_check 成功时按官方语义返回空列表。 */
    private fun readFirstColumn(cursor: Cursor): List<String> = cursor.use {
        buildList {
            while (it.moveToNext()) add(it.getString(0))
        }
    }

    /** 读取必须恰好返回一行的整数 PRAGMA。 */
    private fun readSingleInt(cursor: Cursor): Int = cursor.use {
        require(it.moveToFirst()) { "数据库 PRAGMA 未返回结果" }
        val value = it.getInt(0)
        require(!it.moveToNext()) { "数据库 PRAGMA 返回了多行" }
        value
    }

    /** sqlite_master 返回的标识符仍进行双引号严格转义后才用于 COUNT 查询。 */
    private fun quoteIdentifier(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

    /** 口令副本只覆盖一次 SQLCipher 打开作用域，并在连接关闭后立即清零。 */
    private fun <T> withSqlCipherKeyCopy(
        keyMaterial: DatabaseKeyMaterial,
        block: (ByteArray) -> T
    ): T = keyMaterial.useSqlCipherBytes { managed ->
        val working = managed.copyOf()
        try {
            block(working)
        } finally {
            working.fill(0)
        }
    }

    /** WAL/journal 非空表示仍可能有未合并事务，必须停止迁移。 */
    private fun requireNoHotSidecars(database: File) {
        listOf("-wal", "-journal").forEach { suffix ->
            val sidecar = File(database.path + suffix)
            require(!sidecar.exists() || sidecar.length() == 0L) { "数据库存在未合并 sidecar" }
        }
    }

    /** 只比较 SQLite 固定明文头，避免用错误 API 打开未知文件。 */
    private fun hasPlaintextSqliteHeader(database: File): Boolean {
        if (!database.isFile || database.length() < SQLITE_HEADER.size) return false
        val actual = ByteArray(SQLITE_HEADER.size)
        return runCatching {
            database.inputStream().use { input ->
                var offset = 0
                while (offset < actual.size) {
                    val read = input.read(actual, offset, actual.size - offset)
                    if (read <= 0) return false
                    offset += read
                }
            }
            actual.contentEquals(SQLITE_HEADER)
        }.getOrDefault(false)
    }

    private companion object {
        /** SQLite v3 固定明文文件头。 */
        val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** 合并并截断明文 WAL。 */
        const val WAL_CHECKPOINT_SQL = "PRAGMA wal_checkpoint(TRUNCATE)"

        /** 关闭 WAL，确保 pre-image 只依赖主文件。 */
        const val JOURNAL_MODE_DELETE_SQL = "PRAGMA journal_mode=DELETE"

        /** SQLite 逻辑完整性检查。 */
        const val INTEGRITY_CHECK_SQL = "PRAGMA integrity_check"

        /** SQLCipher 独立页面 HMAC 完整性检查。 */
        const val CIPHER_INTEGRITY_CHECK_SQL = "PRAGMA cipher_integrity_check"

        /** 确认当前句柄确实启用了 SQLCipher。 */
        const val CIPHER_STATUS_SQL = "PRAGMA cipher_status"

        /** sqlcipher_export 不会迁移该值，因此纳入快照与显式目标设置。 */
        const val AUTO_VACUUM_SQL = "PRAGMA auto_vacuum"

        const val AUTO_VACUUM_NONE = 0
        const val AUTO_VACUUM_INCREMENTAL = 2

        /** 路径使用绑定参数，alias 固定，空 KEY 表示 attached 数据库为明文。 */
        const val ATTACH_PLAINTEXT_SQL = "ATTACH DATABASE ? AS plaintext KEY ''"

        /** 从 attached 明文库导出到已加密 main。 */
        const val EXPORT_SQL = "SELECT sqlcipher_export('main', 'plaintext')"

        /** 导出完成后分离明文库。 */
        const val DETACH_PLAINTEXT_SQL = "DETACH DATABASE plaintext"

        /** 获取排除 SQLite 内部对象后的稳定 schema identity。 */
        const val SCHEMA_SQL =
            "SELECT type, name, tbl_name, COALESCE(sql, '') FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name, tbl_name, sql"

        /** 获取全部用户表名，供逐表计数。 */
        const val USER_TABLES_SQL =
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    }
}
