package com.dawncourse.core.data.repository

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedBackupInputTest {
    @Test
    fun unknownLengthStreamIsRejectedAfterLimitWithoutReadingWholePayload() {
        val input = ByteArrayInputStream("123456789".toByteArray())

        assertThrows(BackupInputTooLargeException::class.java) {
            BoundedBackupInput.readUtf8(input, maxBytes = 8)
        }
    }

    @Test
    fun payloadAtLimitIsAccepted() {
        val input = ByteArrayInputStream("12345678".toByteArray())

        assertEquals("12345678", BoundedBackupInput.readUtf8(input, maxBytes = 8))
    }
}
