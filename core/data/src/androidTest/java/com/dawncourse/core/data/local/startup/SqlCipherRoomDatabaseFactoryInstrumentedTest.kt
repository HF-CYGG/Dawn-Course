package com.dawncourse.core.data.local.startup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 SQLCipher Room 首次连接与可复用双 PRAGMA verifier 契约。 */
@RunWith(AndroidJUnit4::class)
class SqlCipherRoomDatabaseFactoryInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun firstOpenAndIntegrityVerifierCanRunAsSeparateReusableSteps() {
        context.deleteDatabase(DATABASE_NAME)
        val passphrase = SqlCipherPassphrase.fromBytes(ByteArray(32) { index -> (index + 1).toByte() })
        val factory = SqlCipherRoomDatabaseFactory(context)
        val database = factory.open(DATABASE_NAME, passphrase)
        try {
            factory.verifyIntegrity(database)
            factory.verifyIntegrity(database)
        } finally {
            database.close()
            passphrase.close()
        }
    }

    private companion object {
        const val DATABASE_NAME = "integrity-factory-test.db"
    }
}
