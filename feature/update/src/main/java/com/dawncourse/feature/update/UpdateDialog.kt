package com.dawncourse.feature.update

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit // URL
) {
    val isDark = isSystemInDarkTheme()
    
    // Custom Colors matching screenshot
    val backgroundColor = if (isDark) Color(0xFF3E2C28) else Color(0xFFFFF0F0)
    val contentColor = if (isDark) Color(0xFFE6E1E5) else Color(0xFF1C1B1F)
    val badgeColor = Color(0xFFE8DEF8) // Light purple/yellowish
    val badgeTextColor = Color(0xFF1D192B)
    val buttonColor = Color(0xFF006D85) // Blueish
    val versionColor = Color(0xFF4FD8EB) // Cyan-ish for dark mode

    val isUpdateAvailable = updateInfo != null
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !(updateInfo?.forceUpdate ?: false),
            dismissOnClickOutside = !(updateInfo?.forceUpdate ?: false)
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = backgroundColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .background(buttonColor.copy(alpha = 0.1f), shape = RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = buttonColor,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                // Title
                Text(
                    text = "版本详情",
                    style = MaterialTheme.typography.headlineSmall,
                    color = contentColor,
                    fontWeight = FontWeight.Bold
                )
                
                // Version
                Text(
                    text = updateInfo.versionName,
                    style = MaterialTheme.typography.titleMedium,
                    color = versionColor,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                )

                // Info Row (Badge + Date)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFFEBC24F), // Yellowish badge
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "功能更新",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black
                        )
                    }
                    
                    Text(
                        text = updateInfo.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.6f)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "更新内容",
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Box(modifier = Modifier.height(150.dp)) {
                         Text(
                            text = updateInfo.updateContent,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.8f),
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Button
                Button(
                    onClick = { onDismiss() }, // For now just dismiss, or onUpdate(updateInfo.downloadUrl)
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "我知道了", color = Color.White)
                }
            }
        }
    }
}
