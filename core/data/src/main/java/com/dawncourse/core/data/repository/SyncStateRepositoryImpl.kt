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

    private val dataStore = context.syncDataStore

    private object Keys {
        val LAST_SYNC_TS = longPreferencesKey("dc_sync.last_ts")
        val LAST_SYNC_SUCCESS = booleanPreferencesKey("dc_sync.last_success")
        val LAST_SYNC_MSG = stringPreferencesKey("dc_sync.last_msg")
        val LAST_SYNC_PROVIDER = stringPreferencesKey("dc_sync.last_provider")
    }

    override val lastSyncInfo: Flow<LastSyncInfo> = dataStore.data.map { prefs ->
        val providerName = prefs[Keys.LAST_SYNC_PROVIDER]
        val provider = providerName?.let { runCatching { SyncProviderType.valueOf(it) }.getOrNull() }
        LastSyncInfo(
            timestamp = prefs[Keys.LAST_SYNC_TS] ?: 0L,
            success = prefs[Keys.LAST_SYNC_SUCCESS] ?: false,
            message = prefs[Keys.LAST_SYNC_MSG] ?: "",
            provider = provider
        )
    }

    override suspend fun setLastSyncInfo(info: LastSyncInfo) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_SYNC_TS] = info.timestamp
            prefs[Keys.LAST_SYNC_SUCCESS] = info.success
            prefs[Keys.LAST_SYNC_MSG] = info.message
            val provider = info.provider
            if (provider == null) {
                prefs.remove(Keys.LAST_SYNC_PROVIDER)
            } else {
                prefs[Keys.LAST_SYNC_PROVIDER] = provider.name
            }
        }
    }
}
