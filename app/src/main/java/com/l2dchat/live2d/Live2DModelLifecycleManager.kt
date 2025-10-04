package com.l2dchat.live2d

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.live2d.demo.full.LAppDelegate
import com.live2d.demo.full.LAppLive2DManager
import com.live2d.sdk.cubism.framework.CubismFramework
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 复刻旧版(增强)生命周期管理器，去除动作倍速控制相关逻辑：
 * - 保留全局干净重置/重新初始化
 * - 保留动作播放/文件播放/动作组探测/详细动作信息
 * - 保留 pose 冲突处理（禁用/恢复）
 * - 移除所有 Live2DMotionSpeedControl 与“倍速”字段/方法
 * - 渲染循环只使用 LAppPal.updateTime()
 */
class Live2DModelLifecycleManager
private constructor(
        private val context: Context,
        private val modelInfo: Live2DModelManager.ModelInfo
) {
    private val backgroundPathRef = AtomicReference<String?>(null)

    init {
        val persisted = Live2DBackgroundTextureHelper.loadPersistedBackgroundPath(context)
        if (!persisted.isNullOrBlank()) {
            backgroundPathRef.set(persisted)
            Log.d(TAG, "初始化背景纹理路径: $persisted")
        } else {
            backgroundPathRef.set(null)
        }
    }

    companion object {
        private const val TAG = "Live2DModelLifecycleManager"
        private val instanceCounter = AtomicInteger(0)
        private val resetLock = Any()
        fun create(
                context: Context,
                modelInfo: Live2DModelManager.ModelInfo
        ): Live2DModelLifecycleManager {
            val id = instanceCounter.incrementAndGet()
            Log.d(TAG, "创建生命周期管理器 #$id : ${modelInfo.name}")
            return Live2DModelLifecycleManager(context, modelInfo).apply { instanceId = id }
        }

        fun performGlobalReset() {
            synchronized(resetLock) {
                try {
                    Log.d(TAG, "执行全局框架重置")
                    try {
                        LAppLive2DManager.getInstance()?.releaseAllModel()
                        LAppLive2DManager.releaseInstance()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放Live2D管理器失败: ${e.message}")
                    }
                    try {
                        LAppDelegate.getInstance()?.let { d ->
                            d.onStop()
                            d.onDestroy()
                        }
                        LAppDelegate.releaseInstance()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放LAppDelegate失败: ${e.message}")
                    }
                    try {
                        if (CubismFramework.isInitialized()) CubismFramework.dispose()
                    } catch (e: Exception) {
                        Log.w(TAG, "关闭Cubism框架失败: ${e.message}")
                    }
                    System.gc()
                } catch (e: Exception) {
                    Log.e(TAG, "全局重置失败", e)
                }
            }
        }

        fun reinitializeGlobalFramework(): Boolean {
            synchronized(resetLock) {
                return try {
                    if (CubismFramework.isInitialized()) {
                        CubismFramework.dispose()
                        Thread.sleep(50)
                    }
                    val ok = ImprovedLive2DRenderer.ensureFrameworkInitialized()
                    if (!ok) Log.e(TAG, "框架重新初始化失败")
                    ok
                } catch (e: Exception) {
                    Log.e(TAG, "重新初始化框架异常", e)
                    false
                }
            }
        }
    }

    private var instanceId: Int = 0

    enum class LifecycleState {
        CREATED,
        INITIALIZING,
        INITIALIZED,
        LOADING,
        LOADED,
        RENDERING,
        DESTROYING,
        DESTROYED
    }

    interface StateCallback {
        fun onStateChanged(newState: LifecycleState, message: String? = null) {}
        fun onError(error: String, exception: Throwable? = null) {}
    }

    private val state = AtomicReference(LifecycleState.CREATED)
    private val isInstanceInitialized = AtomicBoolean(false)
    private val isGLContextReady = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)
    private var stateCallback: StateCallback? = null
    private val gestureLogTag = "${TAG}_Gesture"

    private var renderer: ModelRenderer? = null
    private var glSurfaceView: GLSurfaceView? = null

    fun setStateCallback(cb: StateCallback) {
        stateCallback = cb
    }

    fun initialize(): Boolean {
        if (isDestroyed.get()) {
            notifyError("管理器已销毁", null)
            return false
        }
        if (isInstanceInitialized.get()) return true
        return try {
            setState(LifecycleState.INITIALIZING, "初始化中")
            if (!ensureCleanFrameworkState()) {
                notifyError("无法确保框架干净状态", null)
                return false
            }
            val validation = Live2DModelManager.validateModel(modelInfo)
            if (validation is Live2DModelManager.ValidationResult.Invalid) {
                notifyError("模型验证失败: ${validation.issues.joinToString()}", null)
                return false
            }
            renderer = ModelRenderer(context, modelInfo, this, instanceId)
            isInstanceInitialized.set(true)
            setState(LifecycleState.INITIALIZED, "初始化完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败 #$instanceId", e)
            notifyError("初始化失败: ${e.message}", e)
            false
        }
    }

    private fun ensureCleanFrameworkState(): Boolean =
            try {
                reinitializeGlobalFramework()
            } catch (e: Exception) {
                Log.e(TAG, "干净状态失败", e)
                false
            }

    fun createGLSurfaceView(): GLSurfaceView? {
        if (isDestroyed.get()) {
            notifyError("已销毁", null)
            return null
        }
        if (!isInstanceInitialized.get() && !initialize()) return null
        return try {
            setState(LifecycleState.LOADING, "创建视图")
            val surface =
                    GLSurfaceView(context).apply {
                        setEGLContextClientVersion(2)
                        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                        setZOrderOnTop(false)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

                        val gestureDispatcher =
                                Live2DGestureDispatcher(
                                        object : Live2DGestureDispatcher.Callbacks {
                                            override fun onSingleDown(x: Float, y: Float) {
                                                Log.d(gestureLogTag, "singleDown x=$x y=$y")
                                                LAppDelegate.getInstance().onTouchBegan(x, y)
                                            }
                                            override fun onSingleMove(x: Float, y: Float) {
                                                Log.v(gestureLogTag, "singleMove x=$x y=$y")
                                                LAppDelegate.getInstance().onTouchMoved(x, y)
                                            }
                                            override fun onSingleUp(x: Float, y: Float) {
                                                Log.d(gestureLogTag, "singleUp x=$x y=$y")
                                                LAppDelegate.getInstance().onTouchEnd(x, y)
                                            }
                                            override fun onMultiStart(
                                                    x1: Float,
                                                    y1: Float,
                                                    x2: Float,
                                                    y2: Float
                                            ) {
                                                Log.d(
                                                        gestureLogTag,
                                                        "multiStart p1=($x1,$y1) p2=($x2,$y2)"
                                                )
                                                LAppDelegate.getInstance()
                                                        .onMultiTouchBegan(x1, y1, x2, y2)
                                            }
                                            override fun onMultiMove(
                                                    x1: Float,
                                                    y1: Float,
                                                    x2: Float,
                                                    y2: Float
                                            ) {
                                                Log.v(
                                                        gestureLogTag,
                                                        "multiMove p1=($x1,$y1) p2=($x2,$y2)"
                                                )
                                                LAppDelegate.getInstance()
                                                        .onMultiTouchMoved(x1, y1, x2, y2)
                                            }
                                            override fun onMultiEnd() {
                                                Log.d(gestureLogTag, "multiEnd")
                                            }
                                        }
                                )
                        setOnTouchListener { _, event -> gestureDispatcher.onTouchEvent(event) }
                        isClickable = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
            glSurfaceView = surface
            setState(LifecycleState.LOADED, "视图已创建")
            surface
        } catch (e: Exception) {
            Log.e(TAG, "创建GLSurfaceView失败", e)
            notifyError("创建视图失败: ${e.message}", e)
            null
        }
    }

    fun startRendering() {
        if (isDestroyed.get()) return
        glSurfaceView?.onResume()
        setState(LifecycleState.RENDERING, "渲染中")
    }
    fun pauseRendering() {
        if (isDestroyed.get()) return
        glSurfaceView?.onPause()
        setState(LifecycleState.LOADED, "已暂停")
    }

    fun destroy() {
        if (isDestroyed.getAndSet(true)) return
        try {
            setState(LifecycleState.DESTROYING, "清理中")
            glSurfaceView?.onPause()
            renderer?.cleanup()
            renderer = null
            glSurfaceView = null
            backgroundPathRef.set(null)
            performGlobalReset()
            isInstanceInitialized.set(false)
            isGLContextReady.set(false)
            setState(LifecycleState.DESTROYED, "完成")
        } catch (e: Exception) {
            Log.e(TAG, "销毁异常", e)
            notifyError("销毁异常: ${e.message}", e)
        }
    }

    fun getCurrentState(): LifecycleState = state.get()
    fun isDestroyed(): Boolean = isDestroyed.get()

    internal fun onGLContextReady() {
        isGLContextReady.set(true)
    }
    internal fun onGLContextLost() {
        isGLContextReady.set(false)
    }

    private fun setState(s: LifecycleState, msg: String? = null) {
        val old = state.getAndSet(s)
        if (old != s) stateCallback?.onStateChanged(s, msg)
    }
    private fun notifyError(err: String, ex: Throwable?) {
        Log.e(TAG, err, ex)
        stateCallback?.onError(err, ex)
    }

    fun updateBackgroundTexture(path: String?) {
        backgroundPathRef.set(path)
        Log.d(TAG, "updateBackgroundTexture: 接收路径=$path")
        val gl = glSurfaceView ?: return
        gl.queueEvent {
            Log.d(TAG, "updateBackgroundTexture: 进入GL线程 path=$path")
            applyBackgroundTextureOnGlThread(path)
        }
    }

    private fun applyBackgroundTextureOnGlThread(path: String?) {
        try {
            val delegate = LAppDelegate.getInstance() ?: return
            Live2DBackgroundTextureHelper.applyBackgroundTexture(
                    delegate.textureManager,
                    delegate.view,
                    path
            )
        } catch (e: Exception) {
            Log.e(TAG, "应用背景纹理失败", e)
        }
    }

    private fun applyPendingBackgroundTexture() {
        applyBackgroundTextureOnGlThread(backgroundPathRef.get())
    }

    // ======= 动作 & 信息 =======
    fun playMotion(
            group: String,
            index: Int,
            loop: Boolean,
            priority: Int = com.live2d.demo.LAppDefine.Priority.NORMAL.getPriority()
    ): Boolean {
        if (isDestroyed.get()) return false
        val gl = glSurfaceView ?: return false
        return try {
            gl.queueEvent {
                try {
                    val m = LAppLive2DManager.getInstance()?.getModel(0)
                    if (m != null) {
                        invokeStartMotionReflect(m, group, index, priority, loop, loop, null, null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "播放动作失败: ${e.message}", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "提交动作请求失败", e)
            false
        }
    }

    fun playMotionByFile(motionFilePath: String, loop: Boolean): Boolean {
        if (isDestroyed.get()) return false
        val gl = glSurfaceView ?: return false
        return try {
            gl.queueEvent {
                try {
                    val model = LAppLive2DManager.getInstance()?.getModel(0) ?: return@queueEvent
                    val fileName = motionFilePath.substringAfterLast('/')
                    val motionName = fileName.substringBeforeLast('.')
                    var motionPlayed = false
                    val parts = motionName.split('_')
                    if (parts.size >= 2) {
                        val group = parts[0]
                        val idx =
                                parts.drop(1)
                                        .joinToString("_")
                                        .replace("m", "")
                                        .replace("motion", "")
                                        .toIntOrNull()
                                        ?: 0
                        val motionId =
                                invokeStartMotionReflect(
                                        model,
                                        group,
                                        idx,
                                        com.live2d.demo.LAppDefine.Priority.FORCE.getPriority(),
                                        loop,
                                        loop,
                                        null,
                                        null
                                )
                        if (motionId >= 0) motionPlayed = true
                        else Log.w(TAG, "解析组播放失败: $group[$idx]")
                    }
                    if (!motionPlayed) {
                        val groups =
                                listOf(
                                        "Idle",
                                        "TapBody",
                                        "TapHead",
                                        "Flick",
                                        "Shake",
                                        "Motion",
                                        "Default"
                                )
                        outer@ for (g in groups) {
                            for (i in 0..9) {
                                try {
                                    val id =
                                            invokeStartMotionReflect(
                                                    model,
                                                    g,
                                                    i,
                                                    com.live2d.demo.LAppDefine.Priority.FORCE
                                                            .getPriority(),
                                                    loop,
                                                    loop,
                                                    null,
                                                    null
                                            )
                                    if (id >= 0) {
                                        motionPlayed = true
                                        break@outer
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    if (!motionPlayed) Log.w(TAG, "无法播放任何动作: $motionFilePath")
                } catch (e: Exception) {
                    Log.e(TAG, "GL线程播放动作文件失败: ${e.message}", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "提交动作文件请求失败", e)
            false
        }
    }

    fun stopAllMotions(): Boolean {
        if (isDestroyed.get()) return false
        val gl = glSurfaceView ?: return false
        return try {
            gl.queueEvent {
                try {
                    val model = LAppLive2DManager.getInstance()?.getModel(0)
                    if (model != null) {
                        try {
                            val mmField = model.javaClass.getDeclaredField("motionManager")
                            mmField.isAccessible = true
                            val motionManager = mmField.get(model)
                            if (motionManager != null) {
                                val stop = motionManager.javaClass.getMethod("stopAllMotions")
                                stop.invoke(motionManager)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "停止动作失败降级Idle: ${e.message}")
                            invokeStartMotionReflect(
                                    model,
                                    "Idle",
                                    0,
                                    com.live2d.demo.LAppDefine.Priority.FORCE.getPriority(),
                                    false,
                                    false,
                                    null,
                                    null
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "GL线程停止动作失败:${e.message}", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "提交停止动作请求失败", e)
            false
        }
    }

    fun fetchModelMotionGroups(callback: (List<Pair<String, Int>>) -> Unit) {
        if (isDestroyed.get()) {
            callback(emptyList())
            return
        }
        val gl =
                glSurfaceView
                        ?: run {
                            callback(emptyList())
                            return
                        }
        val mainHandler = Handler(Looper.getMainLooper())
        gl.queueEvent {
            val result = mutableListOf<Pair<String, Int>>()
            try {
                val model = LAppLive2DManager.getInstance()?.getModel(0)
                if (model != null) {
                    try {
                        val settingField = model.javaClass.getDeclaredField("modelSetting")
                        settingField.isAccessible = true
                        val setting =
                                settingField.get(model)
                                        ?: throw IllegalStateException("无modelSetting")
                        val groupCount =
                                setting.javaClass
                                        .getMethod("getMotionGroupCount")
                                        .invoke(setting) as
                                        Int
                        for (i in 0 until groupCount) {
                            try {
                                val groupName =
                                        setting.javaClass
                                                .getMethod("getMotionGroupName", Int::class.java)
                                                .invoke(setting, i) as
                                                String
                                val count =
                                        setting.javaClass
                                                .getMethod("getMotionCount", String::class.java)
                                                .invoke(setting, groupName) as
                                                Int
                                result.add(groupName to count)
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "获取模型设置失败: ${e.message}")
                        val common =
                                listOf("Idle", "TapBody", "TapHead", "Flick", "Shake", "Motion")
                        for (g in common) {
                            try {
                                val id =
                                        invokeStartMotionReflect(
                                                model,
                                                g,
                                                0,
                                                com.live2d.demo.LAppDefine.Priority.NORMAL
                                                        .getPriority(),
                                                false,
                                                false,
                                                null,
                                                null
                                        )
                                if (id >= 0) {
                                    var cnt = 1
                                    for (j in 1..10) {
                                        try {
                                            val t =
                                                    invokeStartMotionReflect(
                                                            model,
                                                            g,
                                                            j,
                                                            com.live2d.demo.LAppDefine.Priority
                                                                    .NORMAL
                                                                    .getPriority(),
                                                            false,
                                                            false,
                                                            null,
                                                            null
                                                    )
                                            if (t >= 0) cnt++ else break
                                        } catch (_: Exception) {
                                            break
                                        }
                                    }
                                    result.add(g to cnt)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取动作组异常: ${e.message}", e)
            } finally {
                mainHandler.post { callback(result) }
            }
        }
    }

    fun playMotionByGroup(groupName: String, index: Int, loop: Boolean): Boolean {
        if (isDestroyed.get()) return false
        val gl = glSurfaceView ?: return false
        return try {
            gl.queueEvent {
                try {
                    val model = LAppLive2DManager.getInstance()?.getModel(0) ?: return@queueEvent
                    val poseDisabled = disablePoseTemporarily(model)
                    val motionId =
                            invokeStartMotionReflect(
                                    model,
                                    groupName,
                                    index,
                                    com.live2d.demo.LAppDefine.Priority.FORCE.getPriority(),
                                    loop,
                                    loop,
                                    { if (poseDisabled) enablePose(model) },
                                    null
                            )
                    if (motionId < 0) {
                        Log.w(TAG, "动作播放失败: $groupName[$index]")
                        if (poseDisabled) enablePose(model)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "GL线程播放动作失败:${e.message}", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "提交播放动作请求失败", e)
            false
        }
    }

    private fun disablePoseTemporarily(model: Any): Boolean =
            try {
                val field = model.javaClass.getDeclaredField("pose")
                field.isAccessible = true
                val pose = field.get(model)
                if (pose != null) {
                    field.set(model, null)
                    true
                } else false
            } catch (_: NoSuchFieldException) {
                false
            } catch (e: Exception) {
                Log.w(TAG, "禁用pose失败:${e.message}")
                false
            }
    private fun enablePose(model: Any) {
        /* 可根据需要在此重新创建pose对象 */
    }

    /**
     * 统一通过反射调用带 loop / loopFadeIn 参数的 startMotion 重载 (group, number, priority, loop, loopFadeIn,
     * finishedCB, beganCB)
     */
    private fun invokeStartMotionReflect(
            model: Any,
            group: String,
            index: Int,
            priority: Int,
            loop: Boolean,
            loopFadeIn: Boolean,
            finished: Any?,
            began: Any?
    ): Int {
        return try {
            val clazz = model.javaClass
            val method =
                    clazz.getMethod(
                            "startMotion",
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            Class.forName(
                                    "com.live2d.sdk.cubism.framework.motion.IFinishedMotionCallback"
                            ),
                            Class.forName(
                                    "com.live2d.sdk.cubism.framework.motion.IBeganMotionCallback"
                            )
                    )
            (method.invoke(model, group, index, priority, loop, loopFadeIn, finished, began) as?
                    Int)
                    ?: -1
        } catch (ns: NoSuchMethodException) {
            // 回退到普通 5 参 (无 loop) 重载；上层确保 Idle 需要 loop 时可以周期性补播
            return try {
                val m2 =
                        model.javaClass.getMethod(
                                "startMotion",
                                String::class.java,
                                Int::class.javaPrimitiveType,
                                Int::class.javaPrimitiveType,
                                Class.forName(
                                        "com.live2d.sdk.cubism.framework.motion.IFinishedMotionCallback"
                                ),
                                Class.forName(
                                        "com.live2d.sdk.cubism.framework.motion.IBeganMotionCallback"
                                )
                        )
                (m2.invoke(model, group, index, priority, finished, began) as? Int) ?: -1
            } catch (e: Exception) {
                Log.w(TAG, "invokeStartMotionReflect 回退失败: ${e.message}")
                -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "invokeStartMotionReflect 失败: ${e.message}")
            -1
        }
    }

    data class MotionDetail(
            val groupName: String,
            val index: Int,
            val fileName: String?,
            val displayName: String
    )
    fun fetchDetailedMotionInfo(callback: (List<Pair<String, List<MotionDetail>>>) -> Unit) {
        if (isDestroyed.get()) {
            callback(emptyList())
            return
        }
        val gl =
                glSurfaceView
                        ?: run {
                            callback(emptyList())
                            return
                        }
        val handler = Handler(Looper.getMainLooper())
        gl.queueEvent {
            val details = mutableListOf<Pair<String, List<MotionDetail>>>()
            try {
                val model = LAppLive2DManager.getInstance()?.getModel(0)
                if (model != null) {
                    try {
                        val settingField = model.javaClass.getDeclaredField("modelSetting")
                        settingField.isAccessible = true
                        val setting = settingField.get(model)
                        if (setting != null) {
                            val groupCount =
                                    setting.javaClass
                                            .getMethod("getMotionGroupCount")
                                            .invoke(setting) as
                                            Int
                            for (i in 0 until groupCount) {
                                try {
                                    val groupName =
                                            setting.javaClass
                                                    .getMethod(
                                                            "getMotionGroupName",
                                                            Int::class.java
                                                    )
                                                    .invoke(setting, i) as
                                                    String
                                    val motionCount =
                                            setting.javaClass
                                                    .getMethod("getMotionCount", String::class.java)
                                                    .invoke(setting, groupName) as
                                                    Int
                                    val list = mutableListOf<MotionDetail>()
                                    for (j in 0 until motionCount) {
                                        try {
                                            val fn =
                                                    setting.javaClass
                                                            .getMethod(
                                                                    "getMotionFileName",
                                                                    String::class.java,
                                                                    Int::class.java
                                                            )
                                                            .invoke(setting, groupName, j) as
                                                            String?
                                            val display =
                                                    fn?.let {
                                                        val n =
                                                                it.substringAfterLast('/')
                                                                        .substringBeforeLast('.')
                                                        "$n (索引$j)"
                                                    }
                                                            ?: "动作 $j"
                                            list.add(MotionDetail(groupName, j, fn, display))
                                        } catch (e: Exception) {
                                            list.add(MotionDetail(groupName, j, null, "动作 $j"))
                                        }
                                    }
                                    details.add(groupName to list)
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        // 备用：通过 ModelInfo.motionFiles 推断
                        val files = modelInfo.motionFiles
                        if (files.isNotEmpty()) {
                            val map = mutableMapOf<String, MutableList<MotionDetail>>()
                            files.forEachIndexed { idx, fp ->
                                val fn = fp.substringAfterLast('/')
                                val base = fn.substringBeforeLast('.')
                                val parts = base.split('_')
                                val g = if (parts.size >= 2) parts[0] else "Motion"
                                val motionIndex =
                                        if (parts.size >= 2)
                                                parts.drop(1)
                                                        .joinToString("_")
                                                        .replace("m", " ")
                                                        .trim()
                                                        .toIntOrNull()
                                                        ?: idx
                                        else idx
                                val display = "$base (索引$motionIndex)"
                                map
                                        .getOrPut(g) { mutableListOf() }
                                        .add(MotionDetail(g, motionIndex, fp, display))
                            }
                            map.forEach { (g, l) -> details.add(g to l.sortedBy { it.index }) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取动作详细信息异常: ${e.message}", e)
            } finally {
                handler.post { callback(details) }
            }
        }
    }

    // ================= 内部渲染器 =================
    private class ModelRenderer(
            private val context: Context,
            private val modelInfo: Live2DModelManager.ModelInfo,
            private val lifecycle: Live2DModelLifecycleManager,
            private val instanceId: Int
    ) : GLSurfaceView.Renderer {
        private var delegate: LAppDelegate? = null
        private var live2DManager: LAppLive2DManager? = null
        private var isSetup = false
        private var frameCount = 0L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val transformContextKey = "app-$instanceId"

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                Log.d(TAG, "onSurfaceCreated #$instanceId : ${modelInfo.name}")
                lifecycle.onGLContextReady()
                initializeLive2D()
                lifecycle.applyPendingBackgroundTexture()
                isSetup = true
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceCreated失败", e)
                lifecycle.notifyError("渲染器初始化失败: ${e.message}", e)
            }
        }

        private fun initializeLive2D() {
            delegate = LAppDelegate.getInstance()
            val act = context.findActivity()
            if (act != null) {
                delegate?.onStart(act)
            } else {
                delegate?.onStartWithContext(context)
            }
            val initContext = act ?: context
            Live2DViewTransformStore.initialize(initContext)
            delegate?.onSurfaceCreated()
            delegate?.setClearColor(0f, 0f, 0f, 0f)
            delegate?.view?.setTransformContextKey(transformContextKey)
            live2DManager = LAppLive2DManager.getInstance()
            live2DManager?.setActiveTransformKey(transformContextKey)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            try {
                delegate?.onSurfaceChanged(width, height)
                live2DManager?.setActiveTransformKey(transformContextKey)
                live2DManager?.let { loadModelForInstance(it) }
                lifecycle.applyPendingBackgroundTexture()
            } catch (e: Exception) {
                Log.e(TAG, "onSurfaceChanged失败", e)
                lifecycle.notifyError("尺寸变化处理失败: ${e.message}", e)
            }
        }

        private fun loadModelForInstance(manager: LAppLive2DManager) {
            try {
                manager.setUpModel()
                val field = manager.javaClass.getDeclaredField("modelDir")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST") val dirs = field.get(manager) as List<String>
                val targetIndex = dirs.indexOf(modelInfo.folderPath)
                if (targetIndex >= 0) {
                    manager.changeScene(targetIndex)
                } else {
                    Log.w(TAG, "未找到模型目录 ${modelInfo.folderPath}, 使用默认模型")
                    manager.changeScene(0)
                }
                scope.launch {
                    delay(100)
                    Log.d(TAG, "模型就绪 #$instanceId")
                }
                // 显式触发 Idle 或回退动作
                triggerIdleOrFallback(manager.getModel(0))
                lifecycle.setState(LifecycleState.RENDERING, "渲染中")
            } catch (e: Exception) {
                Log.e(TAG, "加载模型失败", e)
                lifecycle.notifyError("模型加载失败: ${e.message}", e)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                if (isSetup && !lifecycle.isDestroyed()) {
                    live2DManager?.setActiveTransformKey(transformContextKey)
                    // 注意：delegate.run() 内部已经调用 LAppPal.updateTime() 更新帧间 delta。
                    // 这里如果再次调用会导致两次连续更新时间，相减得到的 deltaTime 极小，
                    // 模型的 update() 里读取到几乎为 0 的 deltaTime，从而表现为“动作静止/极慢”。
                    // 因此这里不再手动调用 LAppPal.updateTime()，只调用 delegate.run()。
                    delegate?.run()
                    frameCount++
                    // 周期性检测当前是否已有动作，否则重新触发 Idle 回路
                    if (frameCount % 180L == 0L) { // 约3秒/帧率60
                        ensureIdleLoop()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onDrawFrame异常", e)
            }
        }

        /** 递归向上查找字段 */
        private fun Any.findFieldRecursive(fieldName: String): Field? {
            var c: Class<*>? = this.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField(fieldName)
                    f.isAccessible = true
                    return f
                } catch (_: NoSuchFieldException) {}
                c = c.superclass
            }
            return null
        }

        /** 启动 Idle/Fallback 逻辑（独立函数便于首次与补播复用） */
        private fun triggerIdleOrFallback(model: Any?) {
            if (model == null) return
            try {
                var started = false
                var usedGroup = "Idle"
                var usedIndex = -1
                // 随机 Idle
                try {
                    val res =
                            model.javaClass
                                    .getMethod(
                                            "startRandomMotion",
                                            String::class.java,
                                            Int::class.javaPrimitiveType
                                    )
                                    .invoke(
                                            model,
                                            com.live2d.demo.LAppDefine.MotionGroup.IDLE.getId(),
                                            com.live2d.demo.LAppDefine.Priority.IDLE.getPriority()
                                    ) as
                                    Int
                    started = res >= 0
                    if (started) {
                        usedIndex = -2
                        usedGroup = com.live2d.demo.LAppDefine.MotionGroup.IDLE.getId()
                        Log.d(TAG, "触发随机Idle motionId=$res")
                    }
                } catch (_: Exception) {}
                // 遍历 Idle 索引
                if (!started) {
                    for (i in 0..9) {
                        try {
                            val id =
                                    model.javaClass
                                            .getMethod(
                                                    "startMotion",
                                                    String::class.java,
                                                    Int::class.javaPrimitiveType,
                                                    Int::class.javaPrimitiveType,
                                                    Boolean::class.javaPrimitiveType,
                                                    Boolean::class.javaPrimitiveType,
                                                    Class.forName(
                                                            "com.live2d.sdk.cubism.framework.motion.IFinishedMotionCallback"
                                                    ),
                                                    Class.forName(
                                                            "com.live2d.sdk.cubism.framework.motion.IBeganMotionCallback"
                                                    )
                                            )
                                            .invoke(
                                                    model,
                                                    "Idle",
                                                    i,
                                                    com.live2d.demo.LAppDefine.Priority.IDLE
                                                            .getPriority(),
                                                    true,
                                                    true,
                                                    null,
                                                    null
                                            ) as
                                            Int
                            if (id >= 0) {
                                started = true
                                usedGroup = "Idle"
                                usedIndex = i
                                Log.d(TAG, "触发Idle[$i] motionId=$id")
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }
                // 回退任意第一 motion 组
                if (!started) {
                    try {
                        val settingField = model.findFieldRecursive("modelSetting")
                        val setting = settingField?.get(model)
                        if (setting != null) {
                            val groupCount =
                                    setting.javaClass
                                            .getMethod("getMotionGroupCount")
                                            .invoke(setting) as
                                            Int
                            if (groupCount > 0) {
                                val firstGroup =
                                        setting.javaClass
                                                .getMethod("getMotionGroupName", Int::class.java)
                                                .invoke(setting, 0) as
                                                String
                                val motionCount =
                                        setting.javaClass
                                                .getMethod("getMotionCount", String::class.java)
                                                .invoke(setting, firstGroup) as
                                                Int
                                for (i in 0 until motionCount) {
                                    try {
                                        val id =
                                                model.javaClass
                                                        .getMethod(
                                                                "startMotion",
                                                                String::class.java,
                                                                Int::class.javaPrimitiveType,
                                                                Int::class.javaPrimitiveType,
                                                                Boolean::class.javaPrimitiveType,
                                                                Boolean::class.javaPrimitiveType,
                                                                Class.forName(
                                                                        "com.live2d.sdk.cubism.framework.motion.IFinishedMotionCallback"
                                                                ),
                                                                Class.forName(
                                                                        "com.live2d.sdk.cubism.framework.motion.IBeganMotionCallback"
                                                                )
                                                        )
                                                        .invoke(
                                                                model,
                                                                firstGroup,
                                                                i,
                                                                com.live2d.demo.LAppDefine.Priority
                                                                        .IDLE
                                                                        .getPriority(),
                                                                true,
                                                                true,
                                                                null,
                                                                null
                                                        ) as
                                                        Int
                                        if (id >= 0) {
                                            started = true
                                            usedGroup = firstGroup
                                            usedIndex = i
                                            Log.d(TAG, "回退组 $firstGroup[$i] motionId=$id")
                                            break
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Idle 回退组启动失败: ${e.message}")
                    }
                }
                if (!started) Log.w(TAG, "未能启动任意 Idle/Fallback 动作")
                else Log.d(TAG, "Idle/Fallback 已启动 group=$usedGroup index=$usedIndex")
            } catch (e: Exception) {
                Log.w(TAG, "triggerIdleOrFallback 失败: ${e.message}")
            }
        }

        /** 确保 Idle 循环存在：若 motionManager 已结束则重启 */
        private fun ensureIdleLoop() {
            try {
                val model = live2DManager?.getModel(0) ?: return
                val mmField =
                        model.findFieldRecursive("motionManager")
                                ?: run {
                                    Log.w(TAG, "ensureIdleLoop: 找不到 motionManager 字段 (递归)")
                                    return
                                }
                val mm = mmField.get(model) ?: return
                val isFinished =
                        try {
                            mm.javaClass.getMethod("isFinished").invoke(mm) as Boolean
                        } catch (_: Exception) {
                            Log.w(TAG, "motionManager.isFinished 反射失败")
                            false
                        }
                if (isFinished) {
                    Log.d(TAG, "Idle 检测: 当前无动作 -> 重新触发 Idle/Fallback")
                    triggerIdleOrFallback(model)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ensureIdleLoop 异常: ${e.message}")
            }
        }

        fun cleanup() {
            try {
                isSetup = false
                scope.cancel()
                live2DManager?.releaseAllModel()
                delegate?.onStop()
                delegate = null
                live2DManager = null
                lifecycle.onGLContextLost()
            } catch (e: Exception) {
                Log.e(TAG, "渲染器清理失败", e)
            }
        }
    }
}

// 旧代码依赖的 Context.findActivity() 扩展
private tailrec fun Context.findActivity(): android.app.Activity? =
        when (this) {
            is android.app.Activity -> this
            is android.content.ContextWrapper -> baseContext.findActivity()
            else -> null
        }
