package com.l2dchat.live2d

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Live2DModelManager {
    private const val TAG = "Live2DModelManager"
    data class MotionInfo(val fileName: String, val filePath: String, val displayName: String)
    data class ModelInfo(
            val name: String,
            val folderPath: String,
            val modelFile: String?,
            val textureFiles: List<String>,
            val motionFiles: List<String>,
            val physicsFile: String? = null,
            val poseFile: String? = null,
            val userDataFile: String? = null
    ) {
        fun getMotionInfoList(): List<MotionInfo> =
                motionFiles
                        .map { fp ->
                            val fn = fp.substringAfterLast('/')
                            MotionInfo(fn, fp, generateMotionDisplayName(fn))
                        }
                        .sortedBy { it.displayName }
    }

    suspend fun scanModels(context: Context): List<ModelInfo> =
            withContext(Dispatchers.IO) {
                val models = mutableListOf<ModelInfo>()
                try {
                    val assetManager = context.assets
                    val rootFiles = assetManager.list("") ?: return@withContext emptyList()
                    rootFiles.forEach { folderName ->
                        try {
                            val folderFiles = assetManager.list(folderName) ?: return@forEach
                            val modelJsonFile = folderFiles.find { it.endsWith(".model3.json") }
                            if (modelJsonFile != null) {
                                val info = collectModelInfo(folderName, folderFiles, assetManager)
                                models.add(info)
                            }
                        } catch (e: IOException) {
                            Log.w(TAG, "扫描文件夹失败: $folderName", e)
                        }
                    }
                    Log.d(TAG, "扫描完成: ${models.size} 模型")
                } catch (e: Exception) {
                    Log.e(TAG, "扫描模型出错", e)
                }
                models
            }

    private suspend fun collectModelInfo(
            folderName: String,
            files: Array<String>,
            assetManager: android.content.res.AssetManager
    ): ModelInfo =
            withContext(Dispatchers.IO) {
                val modelFile = files.find { it.endsWith(".model3.json") }
                val modelPath = modelFile?.let { "$folderName/$it" }
                val textureFiles = mutableListOf<String>()
                val motionFiles = mutableListOf<String>()
                files.forEach { fn ->
                    when {
                        isImageFile(fn) -> textureFiles.add("$folderName/$fn")
                        fn.endsWith(".motion3.json", true) -> motionFiles.add("$folderName/$fn")
                    }
                }
                files.forEach { fn ->
                    try {
                        val sub = assetManager.list("$folderName/$fn")
                        if (sub != null && sub.isNotEmpty()) {
                            sub.forEach { sf ->
                                when {
                                    isImageFile(sf) -> textureFiles.add("$folderName/$fn/$sf")
                                    sf.endsWith(".motion3.json", true) ->
                                            motionFiles.add("$folderName/$fn/$sf")
                                    sf.endsWith(".exp3.json", true) ->
                                            textureFiles.add("$folderName/$fn/$sf")
                                }
                            }
                            sub.forEach { sf ->
                                try {
                                    val subsub = assetManager.list("$folderName/$fn/$sf")
                                    if (subsub != null && subsub.isNotEmpty()) {
                                        subsub.forEach { ssf ->
                                            when {
                                                isImageFile(ssf) ->
                                                        textureFiles.add("$folderName/$fn/$sf/$ssf")
                                                ssf.endsWith(".motion3.json", true) ->
                                                        motionFiles.add("$folderName/$fn/$sf/$ssf")
                                                ssf.endsWith(".exp3.json", true) ->
                                                        textureFiles.add("$folderName/$fn/$sf/$ssf")
                                            }
                                        }
                                    }
                                } catch (_: IOException) {}
                            }
                        }
                    } catch (_: IOException) {}
                }
                val physicsFile =
                        files.find { it.endsWith(".physics3.json", true) }?.let {
                            "$folderName/$it"
                        }
                val poseFile =
                        files.find { it.endsWith(".pose3.json", true) }?.let { "$folderName/$it" }
                val userDataFile =
                        files.find { it.endsWith(".userdata3.json", true) }?.let {
                            "$folderName/$it"
                        }
                val displayName = generateDisplayName(folderName)
                ModelInfo(
                        displayName,
                        folderName,
                        modelPath,
                        textureFiles,
                        motionFiles,
                        physicsFile,
                        poseFile,
                        userDataFile
                )
            }

    private fun isImageFile(fileName: String) =
            fileName.endsWith(".png", true) ||
                    fileName.endsWith(".jpg", true) ||
                    fileName.endsWith(".jpeg", true)
    private fun generateDisplayName(folderName: String) =
            folderName.replace("_", " ").replace("-", " ").split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            }
    private fun generateMotionDisplayName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val spaced = base.replace("_", " ").replace("-", " ")
        return spaced.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun validateModel(modelInfo: ModelInfo): ValidationResult {
        val issues = mutableListOf<String>()
        if (modelInfo.modelFile == null) issues.add("缺少模型文件")
        if (modelInfo.textureFiles.isEmpty()) issues.add("缺少纹理文件")
        if (modelInfo.motionFiles.isEmpty()) issues.add("没有动作文件")
        return if (issues.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(issues)
    }
    sealed class ValidationResult {
        object Valid : ValidationResult()

        data class Invalid(val issues: List<String>) : ValidationResult()
    }
    fun getModelStats(modelInfo: ModelInfo): ModelStats =
            ModelStats(
                    totalFiles =
                            1 +
                                    modelInfo.textureFiles.size +
                                    modelInfo.motionFiles.size +
                                    (if (modelInfo.physicsFile != null) 1 else 0) +
                                    (if (modelInfo.poseFile != null) 1 else 0) +
                                    (if (modelInfo.userDataFile != null) 1 else 0),
                    textureCount = modelInfo.textureFiles.size,
                    motionCount = modelInfo.motionFiles.size,
                    hasPhysics = modelInfo.physicsFile != null,
                    hasPose = modelInfo.poseFile != null,
                    hasUserData = modelInfo.userDataFile != null
            )
    data class ModelStats(
            val totalFiles: Int,
            val textureCount: Int,
            val motionCount: Int,
            val hasPhysics: Boolean,
            val hasPose: Boolean,
            val hasUserData: Boolean
    )
}
