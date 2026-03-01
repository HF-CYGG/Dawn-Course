package com.dawncourse.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dawncourse.core.domain.model.LastSyncInfo
import com.dawncourse.core.domain.model.SyncProviderType
import com.dawncourse.core.domain.repository.SyncStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_state")

/**
 * 同步状态仓库实现
 *
 * 使用 DataStore Preferences 持久化最近一次同步信息。
 */
@Singleton
class SyncStateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncStateRepository {

    private object Keys {
        val LAST_SYNC_TS = longPreferencesKey("dc_sync.last_ts")
        val LAST_SYNC_SUCCESS = booleanPreferencesKey("dc_sync.last_success")
        val LAST_SYNC_MSG = stringPreferencesKey("dc_sync.last_msg")
        val LAST_SYNC_PROVIDER = stringPreferencesKey("dc_sync.last_provider")
    }

    private val ds = context.syncDataStore

    override val lastSyncInfo: Flow<LastSyncInfo> = ds.data.map { p ->
        val ts = p[Keys.LAST_SYNC_TS] ?: 0L
        val ok = p[Keys.LAST_SYNC_SUCCESS] ?: false
        val msg = p[Keys.LAST_SYNC_MSG] ?: ""
        val providerName = p[Keys.LAST_SYNC_PROVIDER]
        val provider = providerName?.let {
            runCatching { SyncProviderType.valueOf(it) }.getOrNull()
        }
        LastSyncInfo(timestamp = ts, success = ok, message = msg, provider = provider)
    }

    override suspend fun setLastSyncInfo(info: LastSyncInfo) {
        ds.edit { p ->
            p[Keys.LAST_SYNC_TS] = info.timestamp
            p[Keys.LAST_SYNC_SUCCESS] = info.success
            p[Keys.LAST_SYNC_MSG] = info.message
            p[Keys.LAST_SYNC_PROVIDER] = info.provider?.name ?: ""
        }
    }
}

