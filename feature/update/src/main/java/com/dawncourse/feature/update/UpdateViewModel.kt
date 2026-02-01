package com.dawncourse.feature.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data class Available(val updateInfo: UpdateInfo) : UpdateUiState()
    data class NoUpdate(val currentVersion: String) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    fun checkUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = UpdateUiState.Checking
            val result = repository.checkUpdate()
            
            result.onSuccess { info ->
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                    
                    if (info.versionCode > currentVersionCode) {
                        _uiState.value = UpdateUiState.Available(info)
                        _showDialog.value = true
                    } else {
                        _uiState.value = UpdateUiState.NoUpdate(info.versionName) // Use info or package info
                        if (manual) {
                            _showDialog.value = true
                        }
                    }
                } catch (e: Exception) {
                    _uiState.value = UpdateUiState.Error(e.message ?: "Unknown error")
                }
            }.onFailure {
                _uiState.value = UpdateUiState.Error(it.message ?: "Network error")
            }
        }
    }

    fun dismissDialog() {
        _showDialog.value = false
    }
}
