package com.dawncourse.feature.import_module

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LlmConsentDialog(
    uiState: ImportUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onSchoolNameChange: (String) -> Unit
) {
    if (!uiState.showLlmConsentDialog) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认上传到云端解析") },
        text = {
            val previewScroll = rememberScrollState()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "将把当前页面脱敏后的完整内容上传至云端解析服务。" +
                        "\n内容长度：${uiState.llmConsentLength} 字符" +
                        (if (uiState.llmConsentSourceUrl.isNotBlank()) "\n来源：${uiState.llmConsentSourceUrl}" else "") +
                        "\n请确认已知情并同意后再继续。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .verticalScroll(previewScroll)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = uiState.llmConsentPreview.ifBlank { "（无预览内容）" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = uiState.llmConsentSchoolName,
                    onValueChange = onSchoolNameChange,
                    label = { Text("学校名称（可选）") },
                    placeholder = { Text("例如：华中科技大学") },
                    supportingText = { Text("请尽量填写完整学校名称，便于归类处理") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCheckedChange(!uiState.llmConsentChecked) }
                ) {
                    Checkbox(
                        checked = uiState.llmConsentChecked,
                        onCheckedChange = onCheckedChange
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "我已知情并同意上传脱敏后的完整内容用于解析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = uiState.llmConsentChecked
            ) {
                Text("同意并上传")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
