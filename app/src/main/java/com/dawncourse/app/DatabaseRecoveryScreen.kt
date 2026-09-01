package com.dawncourse.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dawncourse.core.data.local.startup.DatabaseRecoveryActionFailure
import com.dawncourse.core.data.local.startup.DatabaseRecoveryActionResult
import com.dawncourse.core.data.local.startup.DatabaseRecoveryReason
import com.dawncourse.core.data.local.startup.DatabaseStartupRuntime
import com.dawncourse.core.domain.model.WebDavCredentials
import kotlinx.coroutines.launch

/**
 * 不创建任何 Repository/ViewModel 的数据库恢复页。
 *
 * SAF 与 WebDAV 均调用不依赖原 AppDatabase 的 bootstrap 流程；放弃数据必须二次确认。
 */
@Composable
fun DatabaseRecoveryScreen(
    reason: DatabaseRecoveryReason,
    runtime: DatabaseStartupRuntime,
    onRestartRequired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmAbandon by rememberSaveable { mutableStateOf(false) }

    fun submit(action: suspend () -> DatabaseRecoveryActionResult) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            when (val result = action()) {
                DatabaseRecoveryActionResult.RestartRequired -> onRestartRequired()
                is DatabaseRecoveryActionResult.Failed -> {
                    message = recoveryFailureMessage(context, result.reason)
                    busy = false
                }
            }
        }
    }

    val localBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) submit { runtime.restoreFromLocalBackup(uri) }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.database_recovery_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = recoveryReasonMessage(reason),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.database_recovery_explanation),
                    style = MaterialTheme.typography.bodyMedium
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        localBackupLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                    }
                ) {
                    Text(stringResource(R.string.database_recovery_local_backup))
                }

                Text(
                    text = stringResource(R.string.database_recovery_webdav_title),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = serverUrl,
                    enabled = !busy,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(R.string.database_recovery_webdav_server)) },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = username,
                    enabled = !busy,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.database_recovery_webdav_username)) },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = password,
                    enabled = !busy,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.database_recovery_webdav_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && serverUrl.isNotBlank(),
                    onClick = {
                        val credentials = WebDavCredentials(
                            serverUrl = serverUrl.trim(),
                            username = username,
                            password = password
                        )
                        submit { runtime.restoreFromWebDav(credentials) }
                    }
                ) {
                    Text(stringResource(R.string.database_recovery_webdav_restore))
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { confirmAbandon = true }
                ) {
                    Text(stringResource(R.string.database_recovery_abandon))
                }
                if (busy) CircularProgressIndicator()
                message?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmAbandon) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmAbandon = false },
            title = { Text(stringResource(R.string.database_recovery_abandon_confirm_title)) },
            text = { Text(stringResource(R.string.database_recovery_abandon_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmAbandon = false
                        submit { runtime.abandonInaccessibleData() }
                    }
                ) {
                    Text(stringResource(R.string.database_recovery_abandon_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { confirmAbandon = false }
                ) {
                    Text(stringResource(R.string.database_recovery_cancel))
                }
            }
        )
    }
}

/** 外层锁失败时只给出安全退出提示，不展示无法执行的恢复按钮。 */
@Composable
fun DatabaseStartupBlockedScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.database_startup_blocked_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.database_startup_blocked_message),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

/** 恢复原因不展示异常、路径或密钥细节。 */
@Composable
private fun recoveryReasonMessage(reason: DatabaseRecoveryReason): String = when (reason) {
    DatabaseRecoveryReason.KeyMissingOrInvalid,
    DatabaseRecoveryReason.ExistingEnvelopeCannotBeReplaced,
    DatabaseRecoveryReason.KeyProvisioningFailed -> stringResource(R.string.database_recovery_reason_key)
    DatabaseRecoveryReason.CorruptOrUnknown,
    DatabaseRecoveryReason.CrashRecoveryFailed,
    DatabaseRecoveryReason.MigrationFailed,
    DatabaseRecoveryReason.DatabaseOpenFailed,
    DatabaseRecoveryReason.RecoveryStateCorrupt,
    DatabaseRecoveryReason.RestoreFailed -> stringResource(R.string.database_recovery_reason_data)
}

/** 将稳定失败枚举转换为不会泄漏底层信息的前台提示。 */
private fun recoveryFailureMessage(context: Context, failure: DatabaseRecoveryActionFailure): String = when (failure) {
    DatabaseRecoveryActionFailure.NotInRecoveryMode -> context.getString(R.string.database_recovery_failure_state)
    DatabaseRecoveryActionFailure.BackupUnreadable -> context.getString(R.string.database_recovery_failure_unreadable)
    DatabaseRecoveryActionFailure.BackupTooLarge -> context.getString(R.string.database_recovery_failure_too_large)
    DatabaseRecoveryActionFailure.BackupInvalid -> context.getString(R.string.database_recovery_failure_invalid)
    DatabaseRecoveryActionFailure.WebDavAddressInvalid -> context.getString(R.string.database_recovery_failure_webdav_address)
    DatabaseRecoveryActionFailure.WebDavAuthenticationFailed -> context.getString(R.string.database_recovery_failure_webdav_auth)
    DatabaseRecoveryActionFailure.WebDavUnavailable -> context.getString(R.string.database_recovery_failure_webdav_unavailable)
    DatabaseRecoveryActionFailure.KeyResetFailed,
    DatabaseRecoveryActionFailure.DatabaseRestoreFailed,
    DatabaseRecoveryActionFailure.SettingsRestoreFailed,
    DatabaseRecoveryActionFailure.AbandonFailed -> context.getString(R.string.database_recovery_failure_internal)
}
