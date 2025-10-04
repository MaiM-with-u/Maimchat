package com.l2dchat.live2d

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.live2d.sdk.cubism.framework.math.CubismViewMatrix
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 保存并区分不同上下文（如应用与壁纸）的视图矩阵状态，支持双指拖动/缩放独立生效，并持久化存储。 */
object Live2DViewTransformStore {
    private const val MATRIX_SIZE = 16
    private const val TAG = "L2DMatrixStore"
    private const val PREFS_NAME = "live2d_transform_store"
    private const val PREF_KEY_PREFIX = "matrix:"

    private val matrixStore = ConcurrentHashMap<String, FloatArray>()
    private val initialized = AtomicBoolean(false)
    @Volatile private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else if (prefs == null) {
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun sanitizeKey(raw: String?): String {
        return raw?.takeIf { it.isNotBlank() } ?: "default"
    }

    fun saveFrom(key: String?, matrix: CubismViewMatrix) {
        val safeKey = sanitizeKey(key)
        val sanitized = Live2DMatrixValidator.sanitizeSnapshot(matrix.getArray(), safeKey)
        matrix.setMatrix(sanitized.array)
        val snapshot = sanitized.array.copyOf()
        matrixStore[safeKey] = snapshot
        persistMatrix(safeKey, snapshot)
        Log.v(
                TAG,
                "saveFrom key=$safeKey scale=${snapshot[0]} trans=(${snapshot[12]},${snapshot[13]}) mutated=${sanitized.mutated} details=${sanitized.details}"
        )
    }

    fun saveFrom(key: String?, array: FloatArray?) {
        val safeKey = sanitizeKey(key)
        if (array == null || array.size != MATRIX_SIZE) return
        val result = Live2DMatrixValidator.sanitizeSnapshot(array, safeKey)
        matrixStore[safeKey] = result.array.copyOf()
        persistMatrix(safeKey, result.array)
        Log.v(
                TAG,
                "saveFromRaw key=$safeKey scale=${result.array[0]} trans=(${result.array[12]},${result.array[13]}) mutated=${result.mutated} details=${result.details}"
        )
    }

    fun restoreInto(key: String?, matrix: CubismViewMatrix?): Boolean {
        val safeKey = sanitizeKey(key)
        val target = matrix ?: return false
        var stored = matrixStore[safeKey]
        if (stored == null) {
            stored = loadMatrix(safeKey)
            if (stored != null) {
                val sanitizedStore = Live2DMatrixValidator.sanitizeSnapshot(stored, safeKey)
                matrixStore[safeKey] = sanitizedStore.array.copyOf()
                persistMatrix(safeKey, sanitizedStore.array)
                stored = sanitizedStore.array
            }
        }
        if (stored == null) {
            return false
        }
        val sanitized = Live2DMatrixValidator.sanitizeSnapshot(stored, safeKey)
        matrixStore[safeKey] = sanitized.array.copyOf()
        persistMatrix(safeKey, sanitized.array)
        target.setMatrix(sanitized.array)
        Log.v(
                TAG,
                "restore key=$safeKey scale=${sanitized.array[0]} trans=(${sanitized.array[12]},${sanitized.array[13]}) mutated=${sanitized.mutated} details=${sanitized.details}"
        )
        return true
    }

    fun getMatrixCopy(key: String?): FloatArray? {
        val safeKey = sanitizeKey(key)
        var stored = matrixStore[safeKey]
        if (stored == null) {
            stored = loadMatrix(safeKey)
            if (stored != null) {
                val sanitized = Live2DMatrixValidator.sanitizeSnapshot(stored, safeKey)
                matrixStore[safeKey] = sanitized.array.copyOf()
                persistMatrix(safeKey, sanitized.array)
                stored = sanitized.array
            }
        }
        if (stored == null) return null
        val snapshot = FloatArray(MATRIX_SIZE)
        System.arraycopy(stored, 0, snapshot, 0, MATRIX_SIZE)
        return snapshot
    }

    fun clear(key: String?) {
        val safeKey = sanitizeKey(key)
        matrixStore.remove(safeKey)
        prefs?.edit()?.remove(prefKey(safeKey))?.apply()
    }

    private fun persistMatrix(key: String, array: FloatArray) {
        prefs?.edit()?.putString(prefKey(key), serialize(array))?.apply()
    }

    private fun loadMatrix(key: String): FloatArray? {
        val raw = prefs?.getString(prefKey(key), null) ?: return null
        val parts = raw.split(',')
        if (parts.size != MATRIX_SIZE) {
            prefs?.edit()?.remove(prefKey(key))?.apply()
            return null
        }
        val result = FloatArray(MATRIX_SIZE)
        for (i in 0 until MATRIX_SIZE) {
            val value = parts[i].toFloatOrNull()
            if (value == null) {
                prefs?.edit()?.remove(prefKey(key))?.apply()
                return null
            }
            result[i] = value
        }
        return result
    }

    private fun serialize(array: FloatArray): String =
            array.joinToString(separator = ",") { it.toString() }

    private fun prefKey(key: String) = PREF_KEY_PREFIX + key
}
