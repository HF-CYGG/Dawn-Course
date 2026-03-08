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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_state")

@Singleton
class SyncStateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncStateRepository {
    private val dataStore = context.syncStateDataStore

    private object Keys {
        val TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val SUCCESS = booleanPreferencesKey("last_sync_success")
        val MESSAGE = stringPreferencesKey("last_sync_message")
        val PROVIDER = stringPreferencesKey("last_sync_provider")
    }

    override val lastSyncInfo: Flow<LastSyncInfo> = dataStore.data.map { prefs ->
        val providerName = prefs[Keys.PROVIDER]
        val provider = providerName?.let { runCatching { SyncProviderType.valueOf(it) }.getOrNull() }
        LastSyncInfo(
            timestamp = prefs[Keys.TIMESTAMP] ?: 0L,
            success = prefs[Keys.SUCCESS] ?: false,
            message = prefs[Keys.MESSAGE] ?: "",
            provider = provider
        )
    }

    override suspend fun setLastSyncInfo(info: LastSyncInfo) {
        dataStore.edit { prefs ->
            prefs[Keys.TIMESTAMP] = info.timestamp
            prefs[Keys.SUCCESS] = info.success
            prefs[Keys.MESSAGE] = info.message
            val provider = info.provider
            if (provider == null) {
                prefs.remove(Keys.PROVIDER)
            } else {
                prefs[Keys.PROVIDER] = provider.name
            }
        }
    }
}
