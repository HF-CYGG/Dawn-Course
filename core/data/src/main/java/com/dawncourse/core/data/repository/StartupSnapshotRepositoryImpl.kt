package com.dawncourse.core.data.repository

import android.content.Context
import com.dawncourse.core.data.local.startup.AndroidStartupSnapshotStore
import com.dawncourse.core.domain.model.StartupSnapshot
import com.dawncourse.core.domain.repository.StartupSnapshotReadResult
import com.dawncourse.core.domain.repository.StartupSnapshotRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 启动快照的 data 层入口。
 *
 * 此实现只持有 no-backup 加密文件与 Android Keystore；不注入 AppDatabase、DAO 或任何
 * 会由 Hilt 间接解析 Room 的 Repository，因此冷启动读取不会打开数据库。
 */
@Singleton
class StartupSnapshotRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
) : StartupSnapshotRepository {
    private val store = AndroidStartupSnapshotStore(context)

    override suspend fun read(
        expectedProfileId: Long?,
        nowEpochMillis: Long,
        expectedZoneId: String,
    ): StartupSnapshotReadResult = withContext(Dispatchers.IO) {
        store.read(expectedProfileId, nowEpochMillis, expectedZoneId)
    }

    override suspend fun replace(snapshot: StartupSnapshot): Boolean = withContext(Dispatchers.IO) {
        store.replace(snapshot)
    }

    override suspend fun invalidate() {
        withContext(Dispatchers.IO) { store.invalidate() }
    }
}
