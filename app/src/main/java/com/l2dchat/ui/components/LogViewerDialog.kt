package com.l2dchat.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val logEntries by L2DLogger.entries().collectAsState()
    var selectedModules by remember { mutableStateOf(setOf<LogModule>()) }
    val modules = remember { LogModule.values().toList() }
    val filteredEntries =
            remember(logEntries, selectedModules) {
                val source = logEntries
                if (selectedModules.isEmpty()) source
                else source.filter { it.module in selectedModules }
            }
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    val dateFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    val logText =
            remember(filteredEntries) {
                if (filteredEntries.isEmpty()) ""
                else
                        filteredEntries.asReversed().joinToString(separator = "\n\n") { entry ->
                            buildString {
                                append(dateFormatter.format(Date(entry.timestamp)))
                                append(" · ")
                                append(entry.module.name)
                                append(" · ")
                                append(entry.level.name)
                                append('\n')
                                append(entry.message)
                                entry.throwable?.takeIf { it.isNotBlank() }?.let { stack ->
                                    append("\n")
                                    append(stack.replace("\\n", "\n"))
                                }
                            }
                        }
            }
    AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.FilterList, contentDescription = null) },
            title = { Text("应用日志", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                            text =
                                    if (selectedModules.isEmpty()) "展示全部模块"
                                    else "筛选：" + selectedModules.joinToString { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier =
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        modules.forEach { module ->
                            val selected = module in selectedModules
                            FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedModules =
                                                if (selected) selectedModules - module
                                                else selectedModules + module
                                    },
                                    label = { Text(module.name) },
                                    colors =
                                            FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor =
                                                            MaterialTheme.colorScheme
                                                                    .primaryContainer,
                                                    selectedLabelColor =
                                                            MaterialTheme.colorScheme
                                                                    .onPrimaryContainer
                                            )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    if (filteredEntries.isEmpty()) {
                        Text(
                                text = "暂无日志记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        SelectionContainer {
                            Column(
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .heightIn(min = 180.dp, max = 360.dp)
                                                    .verticalScroll(scrollState)
                            ) {
                                Text(
                                        text = logText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (logText.isNotBlank()) {
                        TextButton(
                                onClick = { clipboardManager.setText(AnnotatedString(logText)) }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("复制全部")
                        }
                    }
                    TextButton(onClick = { scope.launch { L2DLogger.clearLogs() } }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空")
                    }
                }
            }
    )
}
