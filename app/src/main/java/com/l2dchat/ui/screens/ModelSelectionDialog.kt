package com.l2dchat.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.l2dchat.live2d.Live2DModelManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionDialog(
        currentModel: Live2DModelManager.ModelInfo?,
        onModelSelected: (Live2DModelManager.ModelInfo) -> Unit,
        onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<Live2DModelManager.ModelInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                models = Live2DModelManager.scanModels(context)
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }
    Dialog(
            onDismissRequest = onDismiss,
            properties =
                    DialogProperties(
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                            usePlatformDefaultWidth = false
                    )
    ) {
        Card(
                modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f),
                elevation = CardDefaults.cardElevation(8.dp),
                shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                        title = { Text("选择Live2D模型") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            errorMessage = null
                                            try {
                                                models = Live2DModelManager.scanModels(context)
                                            } catch (e: Exception) {
                                                errorMessage = e.message
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    }
                            ) { Icon(Icons.Default.Refresh, contentDescription = null) }
                        }
                )
                when {
                    isLoading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(16.dp))
                                    Text("正在扫描模型...")
                                }
                            }
                    errorMessage != null ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                            }
                    models.isEmpty() ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("没有找到Live2D模型")
                            }
                    else ->
                            LazyColumn(
                                    Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(models) { model ->
                                    ModelSelectionItem(
                                            model = model,
                                            isSelected =
                                                    currentModel?.folderPath == model.folderPath
                                    ) { onModelSelected(model) }
                                }
                            }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectionItem(
        model: Live2DModelManager.ModelInfo,
        isSelected: Boolean,
        onClick: () -> Unit
) {
    Card(
            modifier = Modifier.fillMaxWidth().clickable { onClick() },
            elevation = CardDefaults.cardElevation(if (isSelected) 8.dp else 2.dp),
            colors =
                    CardDefaults.cardColors(
                            containerColor =
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                    )
    ) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                    imageVector =
                            if (isSelected) Icons.Default.CheckCircle else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint =
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color =
                                if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                )
                Text(
                        model.folderPath,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                                if (isSelected)
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                alpha = 0.7f
                                        )
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                val stats = Live2DModelManager.getModelStats(model)
                Row { Text("${stats.motionCount} 动作", style = MaterialTheme.typography.bodySmall) }
            }
            if (isSelected) {
                Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
