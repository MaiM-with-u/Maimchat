package com.l2dchat.live2d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.live2d.demo.LAppDefine
import com.live2d.demo.full.LAppDelegate
import com.live2d.demo.full.LAppLive2DManager
import com.live2d.demo.full.LAppPal
import com.live2d.sdk.cubism.framework.CubismFramework
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 复刻旧版的改进 Live2D 渲染器（迁移到新包名）。 */
class ImprovedLive2DRenderer(
        private val context: Context,
        private val modelInfo: Live2DModelManager.ModelInfo
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ImprovedLive2DRenderer"
        fun ensureFrameworkInitialized(): Boolean {
            return try {
                if (!CubismFramework.isInitialized()) {
                    Log.d(TAG, "初始化Cubism框架")
                    val option = CubismFramework.Option()
                    option.logFunction = LAppPal.PrintLogFunction()
                    option.loggingLevel = LAppDefine.cubismLoggingLevel
                    CubismFramework.startUp(option)
                    CubismFramework.initialize()
                }
                val ok = CubismFramework.isInitialized()
                Log.d(TAG, "框架初始化状态: $ok")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "初始化Cubism框架失败", e)
                false
            }
        }
        fun safeShutdownFramework() {
            try {
                if (CubismFramework.isInitialized()) CubismFramework.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "关闭框架警告", e)
            }
        }
    }

    private var isInitialized = false
    private var isModelLoaded = false
    private var frameCount = 0L

    interface RendererCallback {
        fun onRendererInitialized() {}
        fun onModelLoaded() {}
        fun onRenderError(error: String) {}
        fun onSpeedChanged(speed: Float) {}
    }
    private var callback: RendererCallback? = null
    fun setCallback(cb: RendererCallback?) {
        callback = cb
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated model=${modelInfo.name}")
        try {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            if (!ensureFrameworkInitialized()) {
                callback?.onRenderError("Cubism 初始化失败")
                return
            }
            initializeLAppDelegate()
            loadModel()
            isInitialized = true
            callback?.onRendererInitialized()
        } catch (e: Exception) {
            Log.e(TAG, "SurfaceCreated失败", e)
            callback?.onRenderError("初始化失败: ${e.message}")
        }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        try {
            GLES20.glViewport(0, 0, width, height)
            LAppDelegate.getInstance()?.onSurfaceChanged(width, height)
        } catch (e: Exception) {
            Log.e(TAG, "onSurfaceChanged失败", e)
            callback?.onRenderError("尺寸变化失败: ${e.message}")
        }
    }
    override fun onDrawFrame(gl: GL10?) {
        frameCount++
        try {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (!isInitialized || !isModelLoaded) {
                renderLoadingState()
                return
            }
            Live2DMotionSpeedControl.getModifiedDeltaTime()
            renderModel()
            if (frameCount % 120L == 0L) {
                checkHealth()
                Log.d(TAG, "Heartbeat speed=${Live2DMotionSpeedControl.getCurrentSpeedName()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDrawFrame失败", e)
            callback?.onRenderError("渲染失败: ${e.message}")
        }
    }

    private fun initializeLAppDelegate() {
        val activity = context.findActivity()
        if (activity != null) {
            LAppDelegate.getInstance().onStart(activity)
        } else {
            LAppDelegate.getInstance().onStartWithContext(context)
        }
    }
    private fun loadModel() {
        val mgr =
                LAppLive2DManager.getInstance()
                        ?: throw IllegalStateException("LAppLive2DManager null")
        mgr.releaseAllModel()
        mgr.changeScene(0) // 复刻旧逻辑：加载默认场景
        if (mgr.getModelNum() > 0) {
            isModelLoaded = true
            callback?.onModelLoaded()
            Log.d(TAG, "模型加载成功 count=${mgr.getModelNum()}")
        } else error("模型加载失败")
    }
    private fun renderLoadingState() {
        val alpha = (kotlin.math.sin(frameCount * 0.05) + 0.7).toFloat()
        GLES20.glClearColor(0.2f, 0.2f, 0.4f, alpha)
    }
    private fun renderModel() {
        try {
            LAppDelegate.getInstance()?.run()
        } catch (e: Exception) {
            Log.w(TAG, "渲染警告:${e.message}")
        }
    }
    private fun checkHealth() {
        val mgr = LAppLive2DManager.getInstance()
        if (mgr == null || mgr.getModelNum() == 0) {
            Log.w(TAG, "模型丢失，重载")
            isModelLoaded = false
            loadModel()
        }
    }
    fun release() {
        try {
            LAppLive2DManager.getInstance()?.releaseAllModel()
            LAppDelegate.getInstance()?.onStop()
        } catch (e: Exception) {
            Log.w(TAG, "释放警告", e)
        }
        isInitialized = false
        isModelLoaded = false
        callback = null
    }
    fun setMotionSpeed(speed: Float) {
        Live2DMotionSpeedControl.setSpeed(speed)
        callback?.onSpeedChanged(speed)
    }
    fun nextPresetSpeed() {
        Live2DMotionSpeedControl.nextPresetSpeed()
        callback?.onSpeedChanged(Live2DMotionSpeedControl.getCurrentSpeed())
    }
    fun previousPresetSpeed() {
        Live2DMotionSpeedControl.previousPresetSpeed()
        callback?.onSpeedChanged(Live2DMotionSpeedControl.getCurrentSpeed())
    }
    fun resetSpeed() {
        Live2DMotionSpeedControl.resetToDefault()
        callback?.onSpeedChanged(Live2DMotionSpeedControl.getCurrentSpeed())
    }
}

// 提供旧代码依赖的 Context.findActivity() 扩展
private tailrec fun Context.findActivity(): android.app.Activity? =
        when (this) {
            is android.app.Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }
