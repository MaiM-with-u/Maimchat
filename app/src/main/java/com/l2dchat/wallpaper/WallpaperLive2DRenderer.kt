package com.l2dchat.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.l2dchat.live2d.ImprovedLive2DRenderer
import com.l2dchat.live2d.Live2DBackgroundTextureHelper
import com.l2dchat.live2d.Live2DViewTransformStore
import com.live2d.demo.full.LAppDelegate
import com.live2d.demo.full.LAppLive2DManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class WallpaperLive2DRenderer(private val context: Context) : GLSurfaceView.Renderer {
    companion object {
        private const val TAG = "WallpaperRenderer"
        private const val TRANSFORM_CONTEXT_KEY = "wallpaper"
    }

    private val backgroundLock = Any()
    private val backgroundNeedsUpload = AtomicBoolean(false)
    private var pendingBackgroundBitmap: Bitmap? = null
    private val chatBubbleRenderer = ChatBubbleRenderer(context)

    private val targetModelFolder = AtomicReference<String?>(null)
    private var appliedModelFolder: String? = null
    private var lastModelRetryMs: Long = 0L
    private var modelReadyLogged = false
    private var retryCount = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val retryIntervalMs = 1_000L
    private val maxRetryBeforeReset = 5
    private val pipelineResetInProgress = AtomicBoolean(false)
    private val renderBlockMs = 120L
    private val renderBlockUntilMs = AtomicLong(0L)
    private val maxModelLoadFailureBeforeReset = 3
    private var consecutiveModelLoadFailures = 0
    private val modelWarmupMs = 600L
    private val lastModelApplyMs = AtomicLong(0L)

    private var delegate: LAppDelegate? = null
    private val delegateReady = AtomicBoolean(false)
    private var live2DManager: LAppLive2DManager? = null

    fun setModelFolder(folder: String?) {
        Log.i(TAG, "setModelFolder -> $folder")
        targetModelFolder.set(folder)
        appliedModelFolder = null
        modelReadyLogged = false
        retryCount = 0
        lastModelRetryMs = 0L
        renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
        consecutiveModelLoadFailures = 0
        lastModelApplyMs.set(0L)
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        synchronized(backgroundLock) {
            pendingBackgroundBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            pendingBackgroundBitmap = bitmap
            backgroundNeedsUpload.set(true)
        }
        Log.i(TAG, "Background bitmap queued -> ${bitmap?.width}x${bitmap?.height}")
    }

    private fun applyDelegateDefaults() {
        delegate?.setClearColor(0f, 0f, 0f, 0f)
        delegate?.view?.setTransformContextKey(TRANSFORM_CONTEXT_KEY)
    }

    private fun ensureDelegateReady(allowReinitialize: Boolean): Boolean {
        val currentDelegate = delegate ?: return false
        var ready =
                currentDelegate.getContext() != null &&
                        currentDelegate.view != null &&
                        currentDelegate.getTextureManager() != null
        if (!ready && allowReinitialize) {
            try {
                currentDelegate.onStartWithContext(context)
                applyDelegateDefaults()
                ready =
                        currentDelegate.getContext() != null &&
                                currentDelegate.view != null &&
                                currentDelegate.getTextureManager() != null
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to reinitialize Live2D delegate context", t)
            }
        }
        if (ready && live2DManager == null) {
            try {
                live2DManager = LAppLive2DManager.getInstance()
                live2DManager?.setActiveTransformKey(TRANSFORM_CONTEXT_KEY)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to obtain Live2D manager after delegate init", t)
                ready = false
            }
        }
        delegateReady.set(ready)
        return ready
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Live2DViewTransformStore.initialize(context)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        if (!ImprovedLive2DRenderer.ensureFrameworkInitialized()) {
            Log.e(TAG, "Failed to initialize Cubism framework")
            return
        }
        delegate = LAppDelegate.getInstance()
        delegate?.onStartWithContext(context)
        applyDelegateDefaults()
        delegate?.onSurfaceCreated()
        if (!delegateReady.get()) {
            delegateReady.set(ensureDelegateReady(false))
            if (!delegateReady.get()) {
                delegateReady.set(ensureDelegateReady(true))
            }
        }
        if (delegateReady.get()) {
            live2DManager?.setActiveTransformKey(TRANSFORM_CONTEXT_KEY)
        } else {
            live2DManager = null
            Log.w(TAG, "Delegate not ready after surface creation; model reload deferred")
        }
        Log.i(TAG, "Surface created, delegate ready=${delegate != null}")
        chatBubbleRenderer.onSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        delegate?.onSurfaceChanged(width, height)
        live2DManager?.setActiveTransformKey(TRANSFORM_CONTEXT_KEY)
        Log.i(TAG, "Surface changed -> ${width}x${height}")
        requestModelReload()
        chatBubbleRenderer.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        Log.v(TAG, "onDrawFrame start")
        live2DManager?.setActiveTransformKey(TRANSFORM_CONTEXT_KEY)
        uploadBackgroundIfNeeded()
        ensureModelLoaded()
        renderLive2D()
        chatBubbleRenderer.render()
    }

    fun release() {
        synchronized(backgroundLock) {
            pendingBackgroundBitmap?.takeIf { !it.isRecycled }?.recycle()
            pendingBackgroundBitmap = null
        }
        backgroundNeedsUpload.set(false)
        appliedModelFolder = null
        modelReadyLogged = false
        retryCount = 0
        surfaceWidth = 0
        surfaceHeight = 0
        lastModelRetryMs = 0L
        delegateReady.set(false)
        consecutiveModelLoadFailures = 0
        lastModelApplyMs.set(0L)
        try {
            live2DManager?.releaseAllModel()
        } catch (t: Throwable) {
            Log.w(TAG, "releaseAllModel failed during release", t)
        }
        try {
            LAppLive2DManager.releaseInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "releaseInstance failed during release", t)
        }
        try {
            delegate?.onStop()
        } catch (t: Throwable) {
            Log.w(TAG, "delegate onStop failed during release", t)
        }
        try {
            delegate?.onDestroy()
        } catch (t: Throwable) {
            Log.w(TAG, "delegate onDestroy failed during release", t)
        }
        try {
            LAppDelegate.releaseInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "releaseInstance failed for delegate", t)
        }
        delegate = null
        live2DManager = null
        ImprovedLive2DRenderer.safeShutdownFramework()
        Log.i(TAG, "Renderer release complete")
        chatBubbleRenderer.release()
    }

    private fun ensureModelLoaded() {
        if (live2DManager == null) {
            if (!delegateReady.get() && !ensureDelegateReady(true)) {
                return
            }
            if (live2DManager == null && !ensureDelegateReady(true)) {
                return
            }
        }
        val manager = live2DManager ?: return
        val desired = targetModelFolder.get()
        if (desired == appliedModelFolder && desired != null) return
        if (desired == null && appliedModelFolder != null) return
        logModelLoadAttempt("ensure-pre", desired, manager)
        try {
            val folderToLoad =
                    when {
                        !desired.isNullOrBlank() -> {
                            ensureModelFolderRegistered(manager, desired)
                            desired
                        }
                        else -> {
                            manager.setUpModel()
                            val available = manager.getModelDirList()
                            if (available.isEmpty()) {
                                Log.w(TAG, "No Live2D models detected in assets for wallpaper")
                                null
                            } else {
                                available.first()
                            }
                        }
                    }

            if (folderToLoad.isNullOrBlank()) {
                appliedModelFolder = null
                return
            }

            logModelLoadAttempt("ensure-ready", folderToLoad, manager)

            val success = manager.loadModelByFolder(folderToLoad)
            if (success) {
                val now = SystemClock.elapsedRealtime()
                appliedModelFolder = folderToLoad
                retryCount = 0
                Log.i(TAG, "Model applied -> folder=$appliedModelFolder")
                triggerIdleOrFallback(manager.getModel(0))
                renderBlockUntilMs.set(now + renderBlockMs)
                consecutiveModelLoadFailures = 0
                lastModelApplyMs.set(now)
                logModelLoadAttempt("ensure-success", folderToLoad, manager)
            } else {
                Log.w(TAG, "Failed to load model folder $folderToLoad")
                appliedModelFolder = null
                modelReadyLogged = false
                renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
                logModelLoadAttempt("ensure-fail", folderToLoad, manager)
                handleModelLoadFailure(folderToLoad)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Live2D model for wallpaper", e)
            modelReadyLogged = false
            renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
            logModelLoadAttempt("ensure-error", desired, manager, e.message)
            handleModelLoadFailure(targetModelFolder.get())
        }
    }

    private fun isRenderingReady(): Boolean {
        if (pipelineResetInProgress.get()) return false
        if (!delegateReady.get()) return false
        if (SystemClock.elapsedRealtime() < renderBlockUntilMs.get()) return false
        val desired = targetModelFolder.get()
        if (!desired.isNullOrBlank() && desired != appliedModelFolder) return false
        val manager = live2DManager ?: return false
        val model = manager.getModel(0) ?: return false
        return try {
            model.getModel() != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun renderLive2D() {
        val currentDelegate =
                delegate
                        ?: run {
                            Log.w(TAG, "Live2D delegate missing, skipping frame")
                            return
                        }
        if (!delegateReady.get() && !ensureDelegateReady(true)) {
            Log.w(TAG, "Live2D delegate context not ready, skipping frame")
            return
        }
        if (!isRenderingReady()) {
            ensureModelRendering()
            return
        }

        val shouldSkipPostHandling =
                try {
                    currentDelegate.run()
                    false
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to render Live2D frame", e)
                    handleRenderException(e)
                }
        if (!shouldSkipPostHandling) {
            ensureModelRendering()
        }
    }

    private fun ensureModelRendering() {
        val manager = live2DManager ?: return
        val model = manager.getModel(0)
        val ready =
                model?.let {
                    try {
                        it.getModel() != null
                    } catch (_: Exception) {
                        false
                    }
                }
                        ?: false
        if (ready) {
            if (!modelReadyLogged) {
                Log.i(TAG, "Live2D model rendering confirmed (folder=$appliedModelFolder)")
                modelReadyLogged = true
            }
            renderBlockUntilMs.set(0L)
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastModelApplyMs.get() < modelWarmupMs) {
            return
        }
        if (now - lastModelRetryMs < retryIntervalMs) {
            return
        }
        lastModelRetryMs = now
        modelReadyLogged = false
        retryCount += 1
        val desired = targetModelFolder.get()
        Log.w(
                TAG,
                "Live2D model not ready (attempt=$retryCount, desired=$desired, applied=$appliedModelFolder), scheduling reload"
        )
        if (model != null) {
            triggerIdleOrFallback(model)
        }
        val reloadTarget =
                when {
                    !desired.isNullOrBlank() -> desired
                    !appliedModelFolder.isNullOrBlank() -> appliedModelFolder!!
                    else -> null
                }
        if (!reloadTarget.isNullOrBlank()) {
            logModelLoadAttempt("retry-attempt", reloadTarget, manager, "attempt=$retryCount")
            val reloadSuccess =
                    attemptModelReload(manager, reloadTarget, "render-retry#$retryCount")
            if (reloadSuccess) {
                return
            }
        }
        if (retryCount >= maxRetryBeforeReset) {
            resetLive2DPipeline("retry threshold reached")
        }
    }

    private fun resetLive2DPipeline(reason: String) {
        Log.w(TAG, "Resetting Live2D pipeline ($reason)")
        retryCount = 0
        modelReadyLogged = false
        appliedModelFolder = null
        lastModelRetryMs = 0L
        delegateReady.set(false)
        consecutiveModelLoadFailures = 0
        lastModelApplyMs.set(0L)
        try {
            live2DManager?.releaseAllModel()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release models during reset", t)
        }
        try {
            LAppLive2DManager.releaseInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release manager during reset", t)
        }
        try {
            delegate?.onStop()
        } catch (t: Throwable) {
            Log.w(TAG, "Delegate onStop failed during reset", t)
        }
        try {
            delegate?.onDestroy()
        } catch (t: Throwable) {
            Log.w(TAG, "Delegate onDestroy failed during reset", t)
        }
        try {
            LAppDelegate.releaseInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release delegate singleton", t)
        }
        delegate = null
        live2DManager = null
        ImprovedLive2DRenderer.safeShutdownFramework()
        if (!ImprovedLive2DRenderer.ensureFrameworkInitialized()) {
            Log.e(TAG, "Unable to reinitialize Cubism framework after reset")
            return
        }
        delegate = LAppDelegate.getInstance()
        delegate?.onStartWithContext(context)
        applyDelegateDefaults()
        delegate?.onSurfaceCreated()
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            delegate?.onSurfaceChanged(surfaceWidth, surfaceHeight)
        }
        if (!delegateReady.get()) {
            delegateReady.set(ensureDelegateReady(false))
            if (!delegateReady.get()) {
                delegateReady.set(ensureDelegateReady(true))
            }
        }
        if (delegateReady.get()) {
            live2DManager?.setActiveTransformKey(TRANSFORM_CONTEXT_KEY)
        } else {
            live2DManager = null
            Log.w(TAG, "Delegate not ready after pipeline reset; model reload deferred")
        }
        val persistedPath = Live2DBackgroundTextureHelper.loadPersistedBackgroundPath(context)
        if (!persistedPath.isNullOrBlank()) {
            Live2DBackgroundTextureHelper.applyBackgroundTexture(
                    delegate?.textureManager,
                    delegate?.view,
                    persistedPath
            )
        } else {
            Live2DBackgroundTextureHelper.applyBackgroundTexture(
                    delegate?.textureManager,
                    delegate?.view,
                    path = null
            )
        }
        if (!delegateReady.get() || !attemptImmediateModelReload()) {
            requestModelReload()
        }
        renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
    }

    private fun attemptImmediateModelReload(): Boolean {
        val desired = targetModelFolder.get()
        if (desired.isNullOrBlank()) {
            return false
        }
        if (!delegateReady.get()) {
            Log.w(TAG, "Cannot reload model $desired immediately: delegate not ready")
            return false
        }
        if (live2DManager == null && !ensureDelegateReady(true)) {
            Log.w(TAG, "Cannot reload model $desired immediately: manager unavailable after ensure")
            return false
        }
        val manager = live2DManager
        if (manager == null) {
            Log.w(TAG, "Cannot reload model $desired immediately: manager unavailable")
            return false
        }
        return try {
            val success = manager.loadModelByFolder(desired)
            if (success) {
                val now = SystemClock.elapsedRealtime()
                appliedModelFolder = desired
                modelReadyLogged = false
                retryCount = 0
                lastModelRetryMs = 0L
                triggerIdleOrFallback(manager.getModel(0))
                renderBlockUntilMs.set(now + renderBlockMs)
                Log.i(TAG, "Model re-applied immediately after reset -> folder=$desired")
                consecutiveModelLoadFailures = 0
                lastModelApplyMs.set(now)
                true
            } else {
                Log.w(TAG, "Immediate reload of $desired failed; scheduling retry loop")
                renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
                handleModelLoadFailure(desired)
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Immediate reload of $desired crashed", t)
            renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
            handleModelLoadFailure(desired)
            false
        }
    }

    private fun handleRenderException(error: Throwable): Boolean {
        val rootCause = findRootCause(error)
        val message = rootCause.message.orEmpty()
        val released =
                rootCause is IllegalStateException &&
                        message.contains("Already Released", ignoreCase = true)
        val rendererNpe =
                rootCause is NullPointerException &&
                        rootCause.stackTrace.any {
                            it.className.contains("CubismRendererAndroid") ||
                                    it.className.contains("CubismShaderAndroid")
                        }
        val rendererIndexIssue =
                rootCause is IndexOutOfBoundsException &&
                        rootCause.stackTrace.any {
                            it.className.contains("CubismRendererAndroid") ||
                                    it.className.contains("CubismShaderAndroid")
                        }

        if (released || rendererNpe || rendererIndexIssue) {
            val reason =
                    when {
                        released -> "model released mid-frame"
                        rendererIndexIssue -> "renderer index cache invalid"
                        else -> "renderer cache missing"
                    }
            forcePipelineReset(reason)
            return true
        }

        return false
    }

    private fun ensureModelFolderRegistered(manager: LAppLive2DManager, folder: String) {
        try {
            if (manager.getModelDirList().contains(folder)) {
                return
            }
            Log.d(TAG, "Model folder $folder not cached, refreshing directory list")
            manager.setUpModel()
            if (!manager.getModelDirList().contains(folder)) {
                if (doesModelAssetExist(folder)) {
                    Log.w(TAG, "Assets for model $folder exist but manager list missing entry")
                } else {
                    Log.e(
                            TAG,
                            "Model assets for $folder not found under assets; ensure files are present"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to refresh model directory list for $folder", t)
        }
    }

    private fun doesModelAssetExist(folder: String?): Boolean {
        if (folder.isNullOrBlank()) return false
        return try {
            val assets = delegate?.getContext()?.assets ?: context.assets
            val files = assets.list(folder) ?: return false
            files.any { it.endsWith(".model3.json", ignoreCase = true) }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to inspect assets for $folder", t)
            false
        }
    }

    private fun handleModelLoadFailure(folder: String?) {
        consecutiveModelLoadFailures += 1
        lastModelApplyMs.set(0L)
        if (!folder.isNullOrBlank()) {
            val exists = doesModelAssetExist(folder)
            if (!exists) {
                Log.e(
                        TAG,
                        "Model folder $folder is missing required assets; loading cannot proceed"
                )
            }
        }
        logModelLoadAttempt(
                "failure-count",
                folder,
                live2DManager,
                "count=$consecutiveModelLoadFailures"
        )
        if (consecutiveModelLoadFailures >= maxModelLoadFailureBeforeReset) {
            Log.e(
                    TAG,
                    "Model folder $folder failed to load $consecutiveModelLoadFailures times, forcing pipeline reset"
            )
            consecutiveModelLoadFailures = 0
            forcePipelineReset("model load failure ($folder)")
        } else {
            Log.w(TAG, "Model load failure count=$consecutiveModelLoadFailures for folder=$folder")
        }
    }

    private fun logModelLoadAttempt(
            stage: String,
            target: String?,
            manager: LAppLive2DManager?,
            note: String? = null
    ) {
        val dirs =
                try {
                    manager?.getModelDirList()?.joinToString(prefix = "[", postfix = "]")
                            ?: "<manager-null>"
                } catch (t: Throwable) {
                    "<error:${t.message}>"
                }
        val modelCount =
                try {
                    manager?.getModelNum() ?: -1
                } catch (_: Throwable) {
                    -2
                }
        val applied = appliedModelFolder ?: "<none>"
        val desired = target ?: "<null>"
        val ready = delegateReady.get()
        val resetInFlight = pipelineResetInProgress.get()
        val blockLeft =
                kotlin.math.max(0L, renderBlockUntilMs.get() - SystemClock.elapsedRealtime())
        val failureCount = consecutiveModelLoadFailures
        val assetsExist = target?.let { doesModelAssetExist(it) } ?: false
        val noteSuffix = note?.takeIf { it.isNotBlank() }?.let { " note=$it" } ?: ""
        Log.d(
                TAG,
                "ModelLoad[$stage] target=$desired applied=$applied managerModels=$modelCount dirs=$dirs delegateReady=$ready resetInFlight=$resetInFlight blockMs=$blockLeft failureCount=$failureCount assetsExist=$assetsExist$noteSuffix"
        )
    }

    private fun forcePipelineReset(reason: String) {
        if (!pipelineResetInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Pipeline reset already in progress, skip ($reason)")
            return
        }
        try {
            resetLive2DPipeline(reason)
        } finally {
            pipelineResetInProgress.set(false)
        }
    }

    private fun findRootCause(error: Throwable): Throwable {
        var current: Throwable = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun triggerIdleOrFallback(model: Any?) {
        if (model == null) return
        try {
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
                                        com.live2d.demo.LAppDefine.MotionGroup.IDLE.id,
                                        com.live2d.demo.LAppDefine.Priority.IDLE.priority
                                ) as
                                Int
                if (res >= 0) return
            } catch (_: Exception) {}

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
                                            com.live2d.demo.LAppDefine.Priority.IDLE.priority,
                                            false,
                                            false,
                                            null,
                                            null
                                    ) as
                                    Int
                    if (id >= 0) return
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trigger idle motion failed", e)
        }
    }

    private fun attemptModelReload(
            manager: LAppLive2DManager,
            folder: String,
            reason: String
    ): Boolean {
        logModelLoadAttempt("reload-start", folder, manager, reason)
        return try {
            ensureModelFolderRegistered(manager, folder)
            val success = manager.loadModelByFolder(folder)
            if (success) {
                val now = SystemClock.elapsedRealtime()
                appliedModelFolder = folder
                retryCount = 0
                modelReadyLogged = false
                consecutiveModelLoadFailures = 0
                renderBlockUntilMs.set(now + renderBlockMs)
                lastModelApplyMs.set(now)
                triggerIdleOrFallback(manager.getModel(0))
                Log.i(TAG, "Model reload success ($reason) -> folder=$folder")
                logModelLoadAttempt("reload-success", folder, manager, reason)
            } else {
                Log.w(TAG, "Model reload failed ($reason) -> folder=$folder")
                logModelLoadAttempt("reload-fail", folder, manager, reason)
                handleModelLoadFailure(folder)
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "Model reload crashed ($reason) -> folder=$folder", t)
            logModelLoadAttempt("reload-error", folder, manager, t.message ?: reason)
            handleModelLoadFailure(folder)
            false
        }
    }

    private fun uploadBackgroundIfNeeded() {
        if (!backgroundNeedsUpload.compareAndSet(true, false)) return

        val bitmapSnapshot: Bitmap?
        synchronized(backgroundLock) {
            bitmapSnapshot = pendingBackgroundBitmap
            pendingBackgroundBitmap = null
        }

        val currentDelegate = delegate
        val textureManager = currentDelegate?.textureManager
        val view = currentDelegate?.view
        if (textureManager == null || view == null) {
            Log.w(TAG, "Live2D delegate 未就绪，延迟应用壁纸背景")
            synchronized(backgroundLock) {
                if (pendingBackgroundBitmap == null) {
                    pendingBackgroundBitmap = bitmapSnapshot
                } else {
                    bitmapSnapshot?.takeIf { !it.isRecycled }?.recycle()
                }
            }
            backgroundNeedsUpload.set(true)
            return
        }

        if (bitmapSnapshot != null) {
            Log.i(TAG, "Applying custom background bitmap via texture helper")
            Live2DBackgroundTextureHelper.applyBackgroundTexture(
                    textureManager,
                    view,
                    bitmapSnapshot
            )
        } else {
            Log.i(TAG, "Reverting to default background texture")
            Live2DBackgroundTextureHelper.applyBackgroundTexture(textureManager, view, path = null)
        }
    }

    fun enqueueChatBubble(message: String, fromUser: Boolean) {
        chatBubbleRenderer.enqueueBubble(message, fromUser)
    }

    private fun requestModelReload() {
        Log.d(TAG, "requestModelReload invoked")
        appliedModelFolder = null
        modelReadyLogged = false
        retryCount = 0
        lastModelRetryMs = 0L
        renderBlockUntilMs.set(SystemClock.elapsedRealtime() + renderBlockMs)
        consecutiveModelLoadFailures = 0
        lastModelApplyMs.set(0L)
    }
}
