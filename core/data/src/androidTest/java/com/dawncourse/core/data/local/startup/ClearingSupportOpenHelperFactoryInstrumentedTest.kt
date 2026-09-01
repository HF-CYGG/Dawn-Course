package com.dawncourse.core.data.local.startup

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClearingSupportOpenHelperFactoryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun buildFailureClearsRetainedPassphrase() {
        val original = byteArrayOf(1, 2, 3, 4)
        lateinit var retained: ByteArray
        val factory = ClearingSupportOpenHelperFactory(original) { bytes ->
            retained = bytes
            FrameworkSQLiteOpenHelperFactory()
        }
        val failure = IllegalStateException("simulated Room build failure")

        val thrown = runCatching {
            buildWithOpenHelperFactoryCleanup(factory) { throw failure }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), retained)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), original)
    }

    @Test
    fun delegateCreateFailureClearsRetainedPassphrase() {
        val original = byteArrayOf(5, 6, 7, 8)
        lateinit var retained: ByteArray
        val failure = IllegalStateException("simulated delegate create failure")
        val factory = ClearingSupportOpenHelperFactory(original) { bytes ->
            retained = bytes
            SupportSQLiteOpenHelper.Factory { throw failure }
        }

        val thrown = runCatching { factory.create(configuration()) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), retained)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), original)
    }

    @Test
    fun passphraseRemainsAvailableUntilHelperClosesAndRepeatedCloseIsSafe() {
        val original = byteArrayOf(9, 10, 11, 12)
        lateinit var retained: ByteArray
        val factory = ClearingSupportOpenHelperFactory(original) { bytes ->
            retained = bytes
            FrameworkSQLiteOpenHelperFactory()
        }

        val helper = factory.create(configuration())
        assertArrayEquals(original, retained)
        helper.writableDatabase
        assertArrayEquals(original, retained)

        helper.close()
        helper.close()

        assertTrue(retained.all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(9, 10, 11, 12), original)
    }

    private fun configuration(): SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = fail("不应触发升级")
            })
            .build()
}
