package com.dawncourse.app.crash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 崩溃报告弹窗
 *
 * 复刻自 [com.dawncourse.feature.update.UpdateErrorDialog] 的交互模式
 * （等宽字体展示错误文本 + 一键复制到剪贴板 + Toast 提示），
 * 用于展示上一次启动时捕获到的崩溃堆栈，方便用户手动复制反馈给开发者。
 *
 * 与 UpdateErrorDialog 的差异：
 * - 崩溃堆栈可能较长，文本区域限制最大高度并支持滚动
 * - 增加“忽略”按钮的语义（此处即关闭按钮，点击后 CrashReporter 已清除文件，不会再次弹出）
 *
 * @param report 崩溃报告全文（含机型、系统版本、崩溃线程名、完整堆栈）
 * @param onDismiss 关闭弹窗的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportDialog(
    report: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("上次启动时发生崩溃")
            }
        },
        text = {
            Column {
                Text(
                    text = "App 上次运行时意外退出。请复制以下信息并发送给开发者，帮助定位问题：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 使用半透明错误色背景容器展示崩溃堆栈，限制最大高度并支持滚动
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val scrollState = rememberScrollState()
                    Text(
                        text = report,
                        modifier = Modifier
                            .padding(8.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(scrollState),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        },
        confirmButton = {
            // 复制按钮
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(report))
                    android.widget.Toast.makeText(context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("复制崩溃信息")
            }
        },
        dismissButton = {
            // 忽略按钮：CrashReporter.readAndClear 已经在读取时删除了文件，
            // 这里点击后弹窗不会再次出现
            TextButton(onClick = onDismiss) {
                Text("忽略")
            }
        }
    )
}
