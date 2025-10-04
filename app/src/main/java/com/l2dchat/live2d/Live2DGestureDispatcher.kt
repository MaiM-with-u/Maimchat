package com.l2dchat.live2d

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import kotlin.math.hypot

/** 统一的多指手势分发器，负责在 GL 视图和壁纸之间复用手势识别逻辑。 */
class Live2DGestureDispatcher(private val callbacks: Callbacks) {
    companion object {
        private const val TAG = "L2DGestureDisp"
        private const val SHORT_WINDOW_MS = 45L
        private const val MAX_SINGLE_DELTA_PX = 240f
        private const val MAX_MULTI_CENTER_DELTA_PX = 280f
        private const val MAX_MULTI_RATIO = 2.2f
        private const val MIN_MULTI_RATIO = 0.45f
        private const val MIN_DISTANCE_EPS = 5f
    }
    interface Callbacks {
        fun onSingleDown(x: Float, y: Float)
        fun onSingleMove(x: Float, y: Float)
        fun onSingleUp(x: Float, y: Float)
        fun onMultiStart(x1: Float, y1: Float, x2: Float, y2: Float)
        fun onMultiMove(x1: Float, y1: Float, x2: Float, y2: Float)
        fun onMultiEnd()
    }

    private var primaryPointerId = MotionEvent.INVALID_POINTER_ID
    private var secondaryPointerId = MotionEvent.INVALID_POINTER_ID
    private var isMultiTouch = false
    private var lastMoveLog = 0L
    private var lastSingleX = 0f
    private var lastSingleY = 0f
    private var lastSingleTime = 0L
    private var hasSingleHistory = false
    private var lastPrimaryX = 0f
    private var lastPrimaryY = 0f
    private var lastSecondaryX = 0f
    private var lastSecondaryY = 0f
    private var lastMultiCenterX = 0f
    private var lastMultiCenterY = 0f
    private var lastMultiDistance = 0f
    private var lastMultiTime = 0L
    private var hasMultiHistory = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(
                TAG,
                "event=${MotionEvent.actionToString(event.actionMasked)} pointers=${event.pointerCount} ids=[${(0 until event.pointerCount).joinToString { idx -> event.getPointerId(idx).toString() }}]"
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleActionMove(event)
            MotionEvent.ACTION_POINTER_UP -> handlePointerUp(event)
            MotionEvent.ACTION_UP -> handleActionUp(event)
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun handleActionDown(event: MotionEvent) {
        primaryPointerId = event.getPointerId(0)
        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
        isMultiTouch = false
        Log.d(TAG, "primary DOWN id=$primaryPointerId x=${event.getX(0)} y=${event.getY(0)}")
        recordSingleState(SystemClock.uptimeMillis(), event.getX(0), event.getY(0))
        callbacks.onSingleDown(event.getX(0), event.getY(0))
    }

    private fun handlePointerDown(event: MotionEvent) {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        if (pointerId == primaryPointerId || secondaryPointerId != MotionEvent.INVALID_POINTER_ID) {
            return
        }
        secondaryPointerId = pointerId
        val primaryIndex = ensurePrimaryIndex(event)
        val secondaryIndex = event.findPointerIndex(secondaryPointerId)
        if (primaryIndex == -1 || secondaryIndex == -1) {
            secondaryPointerId = MotionEvent.INVALID_POINTER_ID
            return
        }
        isMultiTouch = true
        Log.d(
                TAG,
                "multi START primaryId=$primaryPointerId secondaryId=$secondaryPointerId p=(${event.getX(primaryIndex)},${event.getY(primaryIndex)}) s=(${event.getX(secondaryIndex)},${event.getY(secondaryIndex)})"
        )
        recordMultiState(
                SystemClock.uptimeMillis(),
                event.getX(primaryIndex),
                event.getY(primaryIndex),
                event.getX(secondaryIndex),
                event.getY(secondaryIndex)
        )
        callbacks.onMultiStart(
                event.getX(primaryIndex),
                event.getY(primaryIndex),
                event.getX(secondaryIndex),
                event.getY(secondaryIndex)
        )
    }

    private fun handleActionMove(event: MotionEvent) {
        if (isMultiTouch &&
                        primaryPointerId != MotionEvent.INVALID_POINTER_ID &&
                        secondaryPointerId != MotionEvent.INVALID_POINTER_ID
        ) {
            val primaryIndex = event.findPointerIndex(primaryPointerId)
            val secondaryIndex = event.findPointerIndex(secondaryPointerId)
            if (primaryIndex != -1 && secondaryIndex != -1) {
                if (shouldIgnoreMultiMove(
                                event.getX(primaryIndex),
                                event.getY(primaryIndex),
                                event.getX(secondaryIndex),
                                event.getY(secondaryIndex)
                        )
                ) {
                    return
                }
                logMove("multi MOVE", event, primaryIndex, secondaryIndex)
                callbacks.onMultiMove(
                        event.getX(primaryIndex),
                        event.getY(primaryIndex),
                        event.getX(secondaryIndex),
                        event.getY(secondaryIndex)
                )
                recordMultiState(
                        SystemClock.uptimeMillis(),
                        event.getX(primaryIndex),
                        event.getY(primaryIndex),
                        event.getX(secondaryIndex),
                        event.getY(secondaryIndex)
                )
                return
            }
        }
        val primaryIndex = event.findPointerIndex(primaryPointerId)
        if (primaryIndex != -1) {
            val x = event.getX(primaryIndex)
            val y = event.getY(primaryIndex)
            if (shouldIgnoreSingleMove(x, y)) {
                return
            }
            logMove("single MOVE", event, primaryIndex)
            callbacks.onSingleMove(x, y)
        }
    }

    private fun handlePointerUp(event: MotionEvent) {
        val pointerId = event.getPointerId(event.actionIndex)
        when (pointerId) {
            secondaryPointerId -> {
                Log.d(TAG, "secondary pointer UP id=$pointerId -> fallback to single")
                dispatchMultiEnd()
                secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                val primaryIndex = event.findPointerIndex(primaryPointerId)
                if (primaryIndex != -1) {
                    recordSingleState(
                            SystemClock.uptimeMillis(),
                            event.getX(primaryIndex),
                            event.getY(primaryIndex)
                    )
                    callbacks.onSingleDown(event.getX(primaryIndex), event.getY(primaryIndex))
                }
            }
            primaryPointerId -> {
                Log.d(TAG, "primary pointer UP id=$pointerId -> promote secondary")
                dispatchMultiEnd()
                if (secondaryPointerId != MotionEvent.INVALID_POINTER_ID) {
                    primaryPointerId = secondaryPointerId
                    secondaryPointerId = MotionEvent.INVALID_POINTER_ID
                    val primaryIndex = event.findPointerIndex(primaryPointerId)
                    if (primaryIndex != -1) {
                        recordSingleState(
                                SystemClock.uptimeMillis(),
                                event.getX(primaryIndex),
                                event.getY(primaryIndex)
                        )
                        callbacks.onSingleDown(event.getX(primaryIndex), event.getY(primaryIndex))
                    }
                } else {
                    Log.d(TAG, "single UP id=$pointerId")
                    callbacks.onSingleUp(
                            event.getX(event.actionIndex),
                            event.getY(event.actionIndex)
                    )
                    reset()
                }
            }
        }
    }

    private fun handleActionUp(event: MotionEvent) {
        Log.d(TAG, "ACTION_UP id=${event.getPointerId(event.actionIndex)}")
        callbacks.onSingleUp(event.getX(event.actionIndex), event.getY(event.actionIndex))
        reset()
    }

    private fun ensurePrimaryIndex(event: MotionEvent): Int {
        val currentIndex = event.findPointerIndex(primaryPointerId)
        if (currentIndex != -1) return currentIndex
        if (event.pointerCount == 0) return -1
        primaryPointerId = event.getPointerId(0)
        return event.findPointerIndex(primaryPointerId)
    }

    private fun reset() {
        primaryPointerId = MotionEvent.INVALID_POINTER_ID
        secondaryPointerId = MotionEvent.INVALID_POINTER_ID
        dispatchMultiEnd()
        hasSingleHistory = false
        hasMultiHistory = false
    }

    private fun dispatchMultiEnd() {
        if (isMultiTouch) {
            Log.d(TAG, "multi END")
            callbacks.onMultiEnd()
        }
        isMultiTouch = false
        hasMultiHistory = false
    }

    private fun logMove(prefix: String, event: MotionEvent, vararg indices: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveLog < 32) return
        lastMoveLog = now
        val coords =
                indices.joinToString { idx ->
                    "id=${event.getPointerId(idx)}(${event.getX(idx)},${event.getY(idx)})"
                }
        Log.v(TAG, "$prefix $coords")
    }

    private fun shouldIgnoreSingleMove(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!hasSingleHistory) {
            recordSingleState(now, x, y)
            return false
        }
        val dt = now - lastSingleTime
        val delta = hypot(x - lastSingleX, y - lastSingleY)
        if (dt in 0 until SHORT_WINDOW_MS && delta > MAX_SINGLE_DELTA_PX) {
            Log.w(TAG, "ignore single move dt=$dt delta=$delta")
            return true
        }
        recordSingleState(now, x, y)
        return false
    }

    private fun shouldIgnoreMultiMove(px: Float, py: Float, sx: Float, sy: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val centerX = (px + sx) * 0.5f
        val centerY = (py + sy) * 0.5f
        val distance = hypot(px - sx, py - sy)
        if (!hasMultiHistory) {
            recordMultiState(now, px, py, sx, sy)
            return false
        }
        val dt = now - lastMultiTime
        val centerDelta = hypot(centerX - lastMultiCenterX, centerY - lastMultiCenterY)
        val ratio = if (lastMultiDistance > MIN_DISTANCE_EPS) distance / lastMultiDistance else 1f
        if (dt in 0 until SHORT_WINDOW_MS &&
                        (centerDelta > MAX_MULTI_CENTER_DELTA_PX ||
                                ratio > MAX_MULTI_RATIO ||
                                ratio < MIN_MULTI_RATIO)
        ) {
            Log.w(TAG, "ignore multi move dt=$dt centerDelta=$centerDelta ratio=$ratio")
            return true
        }
        if (!ratio.isFinite() || !centerDelta.isFinite()) {
            Log.w(
                    TAG,
                    "ignore multi move due to non-finite values ratio=$ratio centerDelta=$centerDelta"
            )
            return true
        }
        recordMultiState(now, px, py, sx, sy)
        return false
    }

    private fun recordSingleState(time: Long, x: Float, y: Float) {
        lastSingleTime = time
        lastSingleX = x
        lastSingleY = y
        hasSingleHistory = true
    }

    private fun recordMultiState(time: Long, px: Float, py: Float, sx: Float, sy: Float) {
        lastPrimaryX = px
        lastPrimaryY = py
        lastSecondaryX = sx
        lastSecondaryY = sy
        lastMultiCenterX = (px + sx) * 0.5f
        lastMultiCenterY = (py + sy) * 0.5f
        lastMultiDistance = hypot(px - sx, py - sy)
        lastMultiTime = time
        hasMultiHistory = true
    }

    private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()
}
