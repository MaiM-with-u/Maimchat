package com.l2dchat.live2d

import android.content.Context
import android.util.Log
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import java.io.InputStream

/**
 * 极简 Live2D 用户模型：
 * 仅支持：
 *  - 从 assets 读取 model3.json + moc + textures
 *  - 随机播放 Idle 组第 0 动作（若存在）
 *  - 每帧更新 + 绘制
 *  - 不实现拖拽、physics、pose、expression、lip sync 等
 */
class MinimalLive2DUserModel(private val context: Context) : CubismUserModel() {
    private var modelDir: String = ""
    private var modelSetting: CubismModelSettingJson? = null
    private var projection: CubismMatrix44 = CubismMatrix44.create()
    private var initialized = false

    private val TAG = "MinimalL2DUserModel"

    fun loadFromModelJson(modelJsonPath: String) {
        if (!CubismFramework.isInitialized()) {
            Log.w(TAG, "CubismFramework 未初始化，放弃加载")
            return
        }
    // 释放旧模型
    delete()
        try {
            modelDir = modelJsonPath.substringBeforeLast('/') + "/"
            val bytes = loadAssetBytes(modelJsonPath)
            modelSetting = CubismModelSettingJson(bytes)
            setupModelInternal()
            setupRenderer(CubismRendererAndroid.create())
            loadTextures()
            initialized = true
            Log.d(TAG, "模型加载完成: $modelJsonPath")
        } catch (e: Exception) {
            Log.e(TAG, "加载模型失败: $modelJsonPath", e)
        }
    }

    private fun setupModelInternal() {
        val setting = modelSetting ?: return
        // 加载 moc
        val mocPath = setting.modelFileName
        if (mocPath.isNullOrEmpty()) {
            Log.e(TAG, "model3.json 未包含 MOC 文件名")
            return
        }
        val mocBytes = loadAssetBytes(modelDir + mocPath)
        loadModel(mocBytes)
        // 初始矩阵：按 SDK 最小示例设置一个适配 2D 的投影
        projection.loadIdentity()
        projection.scale(2.0f, 2.0f)
    }

    private fun loadTextures() {
        val setting = modelSetting ?: return
        val renderer = getRenderer() as? CubismRendererAndroid ?: return
        for (i in 0 until setting.textureCount) {
            val texPath = setting.getTextureFileName(i) ?: continue
            if (texPath.isEmpty()) continue
            try {
                val bitmap = android.graphics.BitmapFactory.decodeStream(openAsset(modelDir + texPath))
                if (bitmap != null) {
                    val texIds = IntArray(1)
                    android.opengl.GLES20.glGenTextures(1, texIds, 0)
                    val texId = texIds[0]
                    android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texId)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                    android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
                    android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    renderer.bindTexture(i, texId)
                    bitmap.recycle()
                    Log.d(TAG, "纹理加载成功 index=$i path=$texPath id=$texId")
                } else {
                    Log.e(TAG, "纹理解码失败 index=$i path=$texPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "纹理加载失败 index=$i path=$texPath", e)
            }
        }
    }

    fun updateAndDraw(viewMatrix: CubismMatrix44) {
        if (!initialized) return
        val delta = (System.nanoTime() / 1_000_000_000.0f) // 简化：使用绝对时间让参数变化最小
        model?.loadParameters()
        // 简单呼吸效果：参数角度微幅正弦
        val angleParam = com.live2d.sdk.cubism.framework.CubismDefaultParameterId.ParameterId.ANGLE_X.id
        val idManager = CubismFramework.getIdManager()
        val idAngle = idManager.getId(angleParam)
        model?.addParameterValue(idAngle, kotlin.math.sin(delta) * 5f)
        model?.saveParameters()
        model?.update()
        // MVP：使用传入矩阵（外部可调缩放）
        (getRenderer() as? CubismRendererAndroid)?.drawModel()
    }

    fun playIdleIfExists() {
        val setting = modelSetting ?: return
        val motionGroup = "Idle"
        val count = setting.getMotionCount(motionGroup)
        if (count <= 0) return
        val file = setting.getMotionFileName(motionGroup, 0)
        if (file.isNullOrEmpty()) return
        try {
            val bytes = loadAssetBytes(modelDir + file)
            val motion = loadMotion(bytes) as? CubismMotion ?: return
            motion.setFadeInTime(setting.getMotionFadeInTimeValue(motionGroup, 0))
            motion.setFadeOutTime(setting.getMotionFadeOutTimeValue(motionGroup, 0))
            motionManager.startMotionPriority(motion, 1)
            Log.d(TAG, "播放 Idle 动作: $file")
        } catch (e: Exception) {
            Log.e(TAG, "加载 Idle 动作失败: $file", e)
        }
    }

    override fun delete() {
        super.delete()
        initialized = false
    }

    private fun loadAssetBytes(path: String): ByteArray = openAsset(path).use { it.readBytes() }
    private fun openAsset(path: String): InputStream = context.assets.open(path)
}
