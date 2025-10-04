package com.l2dchat.wallpaper

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.l2dchat.ui.theme.L2DChatTheme

class WidgetInputActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WidgetInput"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyNoTransition(isEntering = true)
        Log.d(TAG, "onCreate intent=$intent")
        setContent {
            L2DChatTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var text by remember { mutableStateOf("") }
                    var error by remember { mutableStateOf(false) }
                    val context = LocalContext.current
                    Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("发送消息到桌面壁纸", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    if (error) error = false
                                },
                                label = { Text("消息内容") },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth(),
                                isError = error
                        )
                        if (error) {
                            Text(
                                    text = "内容不能为空",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            TextButton(
                                    onClick = {
                                        Log.d(TAG, "Cancel tapped, closing")
                                        finish()
                                    }
                            ) { Text("取消") }
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                    onClick = {
                                        val trimmed = text.trim()
                                        if (trimmed.isBlank()) {
                                            Log.d(TAG, "Send tapped with blank content")
                                            error = true
                                            return@Button
                                        }
                                        Log.d(TAG, "Send tapped with message='${'$'}trimmed'")
                                        WallpaperChatCoordinator.updateWidgetPreview(
                                                context,
                                                trimmed,
                                                fromUser = true
                                        )
                                        val broadcast = Intent(WallpaperComm.ACTION_SEND_MESSAGE)
                                        broadcast.putExtra(
                                                WallpaperComm.EXTRA_MESSAGE_TEXT,
                                                trimmed
                                        )
                                        context.sendBroadcast(broadcast)
                                        Log.d(TAG, "Broadcast sent and activity finishing")
                                        finish()
                                    }
                            ) { Text("发送") }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }

    override fun finish() {
        super.finish()
        applyNoTransition(isEntering = false)
    }

    private fun applyNoTransition(isEntering: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val transitionType =
                    if (isEntering) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE
            overrideActivityTransition(transitionType, 0, 0)
        } else {
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
    }
}
