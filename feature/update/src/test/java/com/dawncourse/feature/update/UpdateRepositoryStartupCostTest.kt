package com.dawncourse.feature.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** 注入更新仓库本身不应建立元数据或安装包网络客户端。 */
class UpdateRepositoryStartupCostTest {
    @Test
    fun constructingRepositoryDoesNotCreateHttpClient() {
        var clientCreations = 0
        var downloaderCreations = 0

        val clientFactory = object : UpdateHttpClientFactory() {
            override fun create(): OkHttpClient {
                clientCreations += 1
                return OkHttpClient()
            }
        }
        val packageDownloader = dagger.Lazy<UpdatePackageDownloader> {
            downloaderCreations += 1
            error("构造仓库时不得解析下载器")
        }

        UpdateRepository(clientFactory, packageDownloader)

        assertEquals(0, clientCreations)
        assertEquals(0, downloaderCreations)
    }
}
