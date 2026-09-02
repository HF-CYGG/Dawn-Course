package com.dawncourse.core.data.local.startup

import android.content.ContextWrapper
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** 恢复页依赖必须等到用户实际发起恢复时才创建。 */
class DatabaseRecoveryStartupCostTest {
    @Test
    fun constructingRecoveryReaderDoesNotCreateNetworkOrJsonDependencies() {
        var clientCreations = 0
        var gsonCreations = 0

        DatabaseRecoveryBackupReader(
            context = ContextWrapper(null),
            clientFactory = {
                clientCreations += 1
                OkHttpClient()
            },
            gsonFactory = {
                gsonCreations += 1
                GsonBuilder().create()
            },
        )

        assertEquals(0, clientCreations)
        assertEquals(0, gsonCreations)
    }
}
