package com.l2dchat.live2d

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/** 复刻旧版完整倍速控制实现 */
object Live2DMotionSpeedControl {
    private const val TAG = "Live2DMotionSpeedControl"
    private val currentSpeed = AtomicReference(1.0f)
    private val presetSpeeds =
            floatArrayOf(
                    0.1f,
                    0.25f,
                    0.5f,
                    0.75f,
                    1.0f,
                    1.25f,
                    1.5f,
                    2.0f,
                    3.0f,
                    5.0f,
                    10.0f,
                    15.0f,
                    20.0f,
                    25.0f,
                    30.0f
            )
    private var currentPresetIndex = 4
    private val speedNames =
            mapOf(
                    0.1f to "0.1x 极慢",
                    0.25f to "0.25x 很慢",
                    0.5f to "0.5x 慢速",
                    0.75f to "0.75x 较慢",
                    1.0f to "1x 正常",
                    1.25f to "1.25x 较快",
                    1.5f to "1.5x 快速",
                    2.0f to "2x 很快",
                    3.0f to "3x 超快",
                    5.0f to "5x 极快",
                    10.0f to "10x 飞速",
                    15.0f to "15x 闪电",
                    20.0f to "20x 光速",
                    25.0f to "25x 瞬移",
                    30.0f to "30x 时空扭曲"
            )
    private var totalSpeedChanges = 0
    private var lastSpeedChangeTime = 0L
    private var baseTimestamp = 0L
    private var scaledTimestamp = 0L
    private val isTimeControlActive = AtomicReference(false)

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.1f, 30.0f)
        val old = currentSpeed.getAndSet(clamped)
        if (old != clamped) {
            totalSpeedChanges++
            lastSpeedChangeTime = System.currentTimeMillis()
            val idx = presetSpeeds.indexOfFirst { it == clamped }
            if (idx >= 0) currentPresetIndex = idx
            Log.d(TAG, "倍速已设置: ${getSpeedName(clamped)} (从 ${getSpeedName(old)})")
        }
    }
    fun getCurrentSpeed(): Float = currentSpeed.get()
    fun getCurrentSpeedName(): String = getSpeedName(getCurrentSpeed())
    private fun getSpeedName(speed: Float) = speedNames[speed] ?: "${speed}x 自定义"
    fun nextPresetSpeed() {
        currentPresetIndex = (currentPresetIndex + 1) % presetSpeeds.size
        setSpeed(presetSpeeds[currentPresetIndex])
    }
    fun previousPresetSpeed() {
        currentPresetIndex =
                if (currentPresetIndex > 0) currentPresetIndex - 1 else presetSpeeds.size - 1
        setSpeed(presetSpeeds[currentPresetIndex])
    }
    fun resetToDefault() {
        currentPresetIndex = 4
        setSpeed(1.0f)
    }

    fun getModifiedDeltaTime(): Float {
        val speed = getCurrentSpeed()
        val now = System.currentTimeMillis()
        val delta =
                if (isTimeControlActive.get()) (now - scaledTimestamp) / 1000f * speed
                else 1f / 60f * speed
        scaledTimestamp = now
        return delta
    }
    fun activateTimeControl() {
        if (!isTimeControlActive.getAndSet(true)) {
            baseTimestamp = System.currentTimeMillis()
            scaledTimestamp = baseTimestamp
        }
    }
    fun getScaledCurrentTimeMillis(): Long {
        val now = System.currentTimeMillis()
        val speed = getCurrentSpeed()
        if (!isTimeControlActive.get()) {
            activateTimeControl()
            return now
        }
        if (baseTimestamp == 0L) {
            baseTimestamp = now
            scaledTimestamp = now
            return now
        }
        val realElapsed = now - baseTimestamp
        val scaledElapsed = (realElapsed * speed).toLong()
        baseTimestamp = now
        scaledTimestamp += scaledElapsed
        return scaledTimestamp
    }
    fun getSpeedStats(): Map<String, Any> =
            mapOf(
                    "currentSpeed" to getCurrentSpeed(),
                    "currentSpeedName" to getCurrentSpeedName(),
                    "totalSpeedChanges" to totalSpeedChanges,
                    "lastSpeedChangeTime" to lastSpeedChangeTime,
                    "availablePresets" to presetSpeeds.size,
                    "currentPresetIndex" to currentPresetIndex
            )
}
