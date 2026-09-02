package com.dawncourse.feature.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** 注入更新仓库本身不应建立网络客户端。 */
class UpdateRepositoryStartupCostTest {
    @Test
    fun constructingRepositoryDoesNotCreateHttpClient() {
        var clientCreations = 0

        val clientFactory = object : UpdateHttpClientFactory() {
            override fun create(): OkHttpClient {
                clientCreations += 1
                return OkHttpClient()
            }
        }

        UpdateRepository(clientFactory)

        assertEquals(0, clientCreations)
    }
}
