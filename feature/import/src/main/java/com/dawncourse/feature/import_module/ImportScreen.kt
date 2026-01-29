package com.dawncourse.feature.import_module

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 课程导入页面
 *
 * 提供多种课程导入方式：
 * 1. 通过 HTML 源码配合 QuickJS 脚本解析导入（适配正方教务系统）
 * 2. 通过 ICS (iCalendar) 文件导入
 *
 * @param onImportSuccess 导入成功后的回调
 * @param modifier 修饰符
 */
@Composable
fun ImportScreen(
    onImportSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: ImportViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            viewModel.runIcsImport(text)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            if (event is ImportEvent.Success) {
                onImportSuccess()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Text(
                text = "导入课程 (QuickJS 测试)",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("粘贴 HTML 源码或点击测试按钮：")
            
            OutlinedTextField(
                value = uiState.htmlContent,
                onValueChange = { viewModel.updateHtmlContent(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("<html>...</html>") }
            )
            
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val script = context.assets.open("parsers/zhengfang.js").use { inputStream ->
                                BufferedReader(InputStreamReader(inputStream)).readText()
                            }
                            viewModel.runImport(script)
                        } catch (_: Exception) {
                        }
                    }
                },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterVertically))
                } else {
                    Text("执行正方系统适配脚本")
                }
            }

            Button(
                onClick = {
                    icsLauncher.launch(arrayOf("text/calendar", "text/plain", "*/*"))
                },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入 ICS 文件")
            }
            
            if (uiState.resultText.isNotEmpty()) {
                Text(
                    text = uiState.resultText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.resultText.startsWith("解析失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
