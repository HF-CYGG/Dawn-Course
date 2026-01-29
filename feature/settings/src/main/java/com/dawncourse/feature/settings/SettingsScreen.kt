package com.dawncourse.feature.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dawncourse.core.domain.model.AppFontStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    val wallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // Persist permission (optional but recommended for long term access)
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not supported
            }
            viewModel.setWallpaperUri(it.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化设置") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Dynamic Color
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "动态取色 (Material You)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "根据壁纸自动生成主题色",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.dynamicColor,
                    onCheckedChange = { viewModel.setDynamicColor(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Transparency
            Text(
                text = "背景透明度",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = settings.transparency,
                onValueChange = { viewModel.setTransparency(it) },
                valueRange = 0f..1f
            )
            Text(
                text = "当前值: ${(settings.transparency * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Wallpaper
            Text(
                text = "自定义壁纸",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(onClick = { wallpaperLauncher.launch("image/*") }) {
                    Text("选择图片")
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (settings.wallpaperUri != null) {
                    Button(onClick = { viewModel.setWallpaperUri(null) }) {
                        Text("清除壁纸")
                    }
                }
            }
            if (settings.wallpaperUri != null) {
                Text(
                    text = "已设置壁纸",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Font Style
            Text(
                text = "字体样式",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                AppFontStyle.entries.forEach { style ->
                    val selected = settings.fontStyle == style
                    Button(
                        onClick = { viewModel.setFontStyle(style) },
                        modifier = Modifier.padding(end = 8.dp),
                        enabled = !selected // Disable if selected
                    ) {
                        Text(style.name)
                    }
                }
            }
        }
    }
}
