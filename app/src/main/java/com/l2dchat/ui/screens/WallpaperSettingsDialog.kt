package com.l2dchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WallpaperSettingsDialog(
        currentPath: String,
        tempPath: String,
        onPickImage: () -> Unit,
        onClearImage: () -> Unit,
        onApply: () -> Unit,
        onDismiss: () -> Unit
) {
        AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("桌面壁纸配置") },
                text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = "当前背景文件:", style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                        value = tempPath,
                                        onValueChange = {},
                                        readOnly = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        label = { Text("文件路径") }
                                )
                                Text(
                                        text =
                                                if (currentPath.isBlank()) "尚未设置背景"
                                                else "已应用背景: $currentPath",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                )
                                Divider()
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(onClick = onPickImage) { Text("选择图片") }
                                        Button(onClick = onClearImage) { Text("清除") }
                                }
                                Text(
                                        text = "支持常见图片格式，建议 16:9 或 9:16，过大的图片会自动压缩到 2048px 以内。",
                                        style = MaterialTheme.typography.bodySmall
                                )
                        }
                },
                confirmButton = { TextButton(onClick = onApply) { Text("应用") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
}
