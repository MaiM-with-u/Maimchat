package com.l2dchat.live2d

import android.util.Log
import com.live2d.demo.LAppDefine
import com.live2d.sdk.cubism.framework.math.CubismViewMatrix
import kotlin.math.abs

/**
 * Sanitises Live2D view matrices to avoid NaN/shear/extreme values that would otherwise cause
 * wallpaper transforms to explode after gesture glitches.
 */
object Live2DMatrixValidator {
    private const val TAG = "L2DMatrixValidator"

    private val IDENTITY =
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private val SCALE_MIN = LAppDefine.Scale.MIN.value
    private val SCALE_MAX = LAppDefine.Scale.MAX.value
    private const val SCALE_EPS = 1e-3f
    private const val ASPECT_EPS = 0.02f
    private const val TRANSLATE_PADDING = 1.1f

    data class Result(val array: FloatArray, val mutated: Boolean, val details: List<String>)

    fun sanitize(matrix: CubismViewMatrix, key: String?): Boolean {
        val result = sanitizeSnapshot(matrix.getArray(), key)
        matrix.setMatrix(result.array)
        return result.mutated
    }

    fun sanitizeSnapshot(source: FloatArray?, key: String?): Result {
        val safeKey = key ?: "default"
        if (source == null || source.size != 16) {
            val details = listOf("source=nullOrInvalid -> identity")
            val result = Result(IDENTITY.copyOf(), true, details)
            logResult(safeKey, result)
            return result
        }
        val work = source.copyOf()
        var mutated = false
        val details = mutableListOf<String>()

        if (!work.allFinite()) {
            Log.w(TAG, "[$safeKey] matrix contained non-finite entries; reset to identity")
            work.overwriteWithIdentity()
            mutated = true
            details += "nonFinite -> identity"
        }

        val scaleX = work[0]
        val scaleY = work[5]
        var harmonisedScale = ((scaleX + scaleY) * 0.5f).takeIf { it.isFinite() } ?: 1f
        harmonisedScale = harmonisedScale.coerceIn(SCALE_MIN, SCALE_MAX)
        if (abs(scaleX - harmonisedScale) > ASPECT_EPS || abs(scaleY - harmonisedScale) > ASPECT_EPS
        ) {
            work[0] = harmonisedScale
            work[5] = harmonisedScale
            mutated = true
            Log.w(TAG, "[$safeKey] scale harmonised to $harmonisedScale (sx=$scaleX sy=$scaleY)")
            details += "scale harmonised from ($scaleX,$scaleY)"
        }

        val translateLimitX =
                abs(LAppDefine.MaxLogicalView.RIGHT.value) * harmonisedScale * TRANSLATE_PADDING
        val translateLimitY =
                abs(LAppDefine.MaxLogicalView.TOP.value) * harmonisedScale * TRANSLATE_PADDING
        val clampedTx = work[12].coerceIn(-translateLimitX, translateLimitX)
        val clampedTy = work[13].coerceIn(-translateLimitY, translateLimitY)
        if (abs(work[12] - clampedTx) > SCALE_EPS || abs(work[13] - clampedTy) > SCALE_EPS) {
            Log.w(TAG, "[$safeKey] translation clamped to ($clampedTx,$clampedTy)")
            work[12] = clampedTx
            work[13] = clampedTy
            mutated = true
            details += "translate clamped"
        }

        val zeroIndices = intArrayOf(1, 2, 3, 4, 6, 7, 8, 9, 11, 14)
        zeroIndices.forEach { idx ->
            if (!work[idx].isNearZero()) {
                work[idx] = 0f
                mutated = true
                details += "index$idx->0"
            }
        }
        if (abs(work[10] - 1f) > SCALE_EPS) {
            work[10] = 1f
            mutated = true
            details += "m22 reset"
        }
        if (abs(work[15] - 1f) > SCALE_EPS) {
            work[15] = 1f
            mutated = true
            details += "m33 reset"
        }

        val result = Result(work, mutated, details)
        logResult(safeKey, result)
        return result
    }

    private fun FloatArray.overwriteWithIdentity() {
        System.arraycopy(IDENTITY, 0, this, 0, IDENTITY.size)
    }

    private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()

    private fun Float.isNearZero(): Boolean = abs(this) < SCALE_EPS

    private fun FloatArray.allFinite(): Boolean = all { it.isFinite() }

    private fun logResult(key: String?, result: Result) {
        val safeKey = key ?: "default"
        Log.d(
                TAG,
                "[$safeKey] sanitized scale=${String.format("%.4f", result.array[0])} " +
                        "trans=(${String.format("%.4f", result.array[12])},${String.format("%.4f", result.array[13])}) " +
                        "mutated=${result.mutated} details=${if (result.details.isEmpty()) "-" else result.details.joinToString(";")}"
        )
    }
}
