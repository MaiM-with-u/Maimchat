package com.l2dchat.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.l2dchat.chat.service.ChatServiceClient.ChatMessageSnapshot
import com.l2dchat.live2d.Live2DGestureDispatcher
import com.live2d.demo.full.LAppDelegate
import com.l2dchat.logging.L2DLogger
import com.l2dchat.logging.LogModule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 基础 Live2D 动态壁纸骨架： 后续需要接入现有的 Live2D 渲染器 (ImprovedLive2DRenderer / LifecycleManager) 放到一个离屏或直接 GL
 * 上下文。 当前版本先用占位绘制与手势缩放/拖动逻辑，消息接收与气泡缓冲。
 */
class Live2DWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = L2DEngine()

    inner class L2DEngine : Engine() {
        private val logger = L2DLogger.module(LogModule.WALLPAPER)
        private val prefs by lazy {
            this@Live2DWallpaperService.applicationContext.getSharedPreferences(
                    WallpaperComm.PREF_WALLPAPER,
                    Context.MODE_PRIVATE
            )
        }
        private var renderer = WallpaperLive2DRenderer(applicationContext)
        private val bubbleListener =
                object : WallpaperChatCoordinator.Listener {
                    override fun onMessageAppended(message: ChatMessageSnapshot) {
                        renderer.enqueueChatBubble(message.content, message.isFromUser)
                    }
                }
        private val glThreadGuard = Any()
        @Volatile private var glThread: WallpaperGLThread? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val restartPending = AtomicBoolean(false)
        private val serviceResetPending = AtomicBoolean(false)
        @Volatile private var scheduledRestartJob: Job? = null
        @Volatile private var scheduledServiceResetJob: Job? = null
        private var consecutiveFailures = 0
        private var threadRestartCount = 0
        private var lastFailureAt = 0L
        private val failureResetWindowMs = 10_000L
        private val failuresBeforeThreadRestart = 3
        private val threadRestartLimitBeforeServiceReset = 2
        private val restartBaseDelayMs = 750L
        private val restartMaxDelayMs = 5_000L
        private val serviceResetDelayMs = 3_000L
        private val threadJoinTimeoutMs = 1_200L
        private val renderEventListener =
                object : WallpaperGLThread.RenderEventListener {
                    override fun onRenderError(
                            stage: WallpaperGLThread.RenderStage,
                            throwable: Throwable?
                    ) {
                        handleGlRenderError(stage, throwable)
                    }

                    override fun onRenderSuccess() {
                        handleGlRenderSuccess()
                    }
                }
        private val gestureDispatcher =
                Live2DGestureDispatcher(
                        object : Live2DGestureDispatcher.Callbacks {
                            override fun onSingleDown(x: Float, y: Float) {
                                LAppDelegate.getInstance().onTouchBegan(x, y)
                            }

                            override fun onSingleMove(x: Float, y: Float) {
                                LAppDelegate.getInstance().onTouchMoved(x, y)
                            }

                            override fun onSingleUp(x: Float, y: Float) {
                                LAppDelegate.getInstance().onTouchEnd(x, y)
                            }

                            override fun onMultiStart(x1: Float, y1: Float, x2: Float, y2: Float) {
                                LAppDelegate.getInstance().onMultiTouchBegan(x1, y1, x2, y2)
                            }

                            override fun onMultiMove(x1: Float, y1: Float, x2: Float, y2: Float) {
                                LAppDelegate.getInstance().onMultiTouchMoved(x1, y1, x2, y2)
                            }

                            override fun onMultiEnd() {}
                        }
                )

        @Volatile private var currentSurfaceHolder: SurfaceHolder? = null
        @Volatile private var currentSurfaceWidth: Int = 0
        @Volatile private var currentSurfaceHeight: Int = 0
        @Volatile private var engineVisible: Boolean = false

        private fun startGlThread(reason: String) {
            logger.info("Starting GL thread ($reason)")
            val thread = WallpaperGLThread(renderer, renderEventListener)
            synchronized(glThreadGuard) { glThread = thread }
            thread.start()
            val holderSnapshot = currentSurfaceHolder
            val widthSnapshot = currentSurfaceWidth
            val heightSnapshot = currentSurfaceHeight
            if (holderSnapshot != null) {
                thread.onSurfaceCreated(holderSnapshot)
                if (widthSnapshot > 0 && heightSnapshot > 0) {
                    thread.onSurfaceChanged(widthSnapshot, heightSnapshot)
                }
            }
            if (!engineVisible) {
                thread.onPause()
            }
        }

        private fun handleGlRenderSuccess() {
            consecutiveFailures = 0
            lastFailureAt = 0L
            restartPending.set(false)
            threadRestartCount = 0
            scheduledRestartJob?.cancel()
            scheduledRestartJob = null
            scheduledServiceResetJob?.cancel()
            scheduledServiceResetJob = null
            serviceResetPending.set(false)
        }

        private fun handleGlRenderError(
                stage: WallpaperGLThread.RenderStage,
                throwable: Throwable?
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFailureAt > failureResetWindowMs) {
                consecutiveFailures = 0
            }
            lastFailureAt = now
            consecutiveFailures += 1
        logger.error(
            "Renderer error stage=${stage.name} attempt=$consecutiveFailures",
            throwable = throwable,
            throttleMs = 1_000L,
            throttleKey = "render_error_${stage.name}"
        )
            if (consecutiveFailures < failuresBeforeThreadRestart) {
                return
            }
            if (restartPending.compareAndSet(false, true)) {
                val delayMs =
                        (restartBaseDelayMs * (threadRestartCount + 1)).coerceAtMost(
                                restartMaxDelayMs
                        )
                scheduledRestartJob?.cancel()
                scheduledRestartJob =
                        scope.launch {
                            try {
                                delay(delayMs)
                                performGlThreadRestart(stage)
                            } finally {
                                restartPending.set(false)
                                scheduledRestartJob = null
                            }
                        }
            }
        }

        private suspend fun performGlThreadRestart(stage: WallpaperGLThread.RenderStage) {
            logger.warn(
                    "Restarting GL thread due to persistent errors (stage=${stage.name})"
            )
            val currentThread = synchronized(glThreadGuard) { glThread }
            currentThread?.shutdown()
            try {
                currentThread?.join(threadJoinTimeoutMs)
            } catch (t: InterruptedException) {
                logger.warn("Interrupted while waiting for GL thread to join", t)
            }
            synchronized(glThreadGuard) {
                if (glThread === currentThread) {
                    glThread = null
                }
            }
            renderer.release()
            consecutiveFailures = 0
            threadRestartCount += 1
            startGlThread("restart after ${stage.name}")
            lastRequestedModelFolder?.let { renderer.setModelFolder(it) }
            loadBackgroundAsync(lastRequestedBackgroundPath, force = true)
            if (threadRestartCount > threadRestartLimitBeforeServiceReset) {
                scheduleServiceReset(stage)
            }
        }

        private fun scheduleServiceReset(stage: WallpaperGLThread.RenderStage) {
            if (!serviceResetPending.compareAndSet(false, true)) {
                return
            }
            scheduledServiceResetJob?.cancel()
            scheduledServiceResetJob =
                    scope.launch {
                        try {
                            delay(serviceResetDelayMs)
                            performServiceReset(stage)
                        } finally {
                            serviceResetPending.set(false)
                            scheduledServiceResetJob = null
                        }
                    }
        }

        private suspend fun performServiceReset(stage: WallpaperGLThread.RenderStage) {
            logger.error("Performing wallpaper service pipeline reset (stage=${stage.name})")
            val currentThread = synchronized(glThreadGuard) { glThread }
            currentThread?.shutdown()
            try {
                currentThread?.join(threadJoinTimeoutMs)
            } catch (t: InterruptedException) {
                logger.warn("Interrupted while waiting for GL thread shutdown", t)
            }
            synchronized(glThreadGuard) {
                if (glThread === currentThread) {
                    glThread = null
                }
            }
            renderer.release()
            renderer = WallpaperLive2DRenderer(applicationContext)
            threadRestartCount = 0
            consecutiveFailures = 0
            scheduledRestartJob?.cancel()
            scheduledRestartJob = null
            restartPending.set(false)
            startGlThread("service reset after ${stage.name}")
            lastRequestedModelFolder?.let { renderer.setModelFolder(it) }
            loadBackgroundAsync(lastRequestedBackgroundPath, force = true)
        }

        @Volatile private var lastRequestedBackgroundPath: String? = null
        @Volatile private var lastAppliedBackgroundPath: String? = null
        @Volatile private var lastRequestedModelFolder: String? = null

        private val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.action) {
                            WallpaperComm.ACTION_SEND_MESSAGE -> {
                                val msg = intent.getStringExtra(WallpaperComm.EXTRA_MESSAGE_TEXT)
                                if (!msg.isNullOrBlank()) {
                    logger.debug(
                        "Received widget message: $msg",
                        throttleMs = 1_000L,
                        throttleKey = "widget_receive"
                    )
                                    scope.launch {
                                        val appCtx = this@Live2DWallpaperService.applicationContext
                                        val success =
                                                try {
                                                    WallpaperChatCoordinator.sendMessage(
                                                            appCtx,
                                                            msg
                                                    )
                                                } catch (t: Throwable) {
                                                    logger.error(
                                                            "Dispatch widget message failed",
                                                            t
                                                    )
                                                    false
                                                }
                                        if (!success) {
                                            WallpaperChatCoordinator.updateWidgetPreview(
                                                    appCtx,
                                                    "发送失败，请检查连接",
                                                    fromUser = false
                                            )
                                        }
                                    }
                                }
                            }
                            WallpaperComm.ACTION_REFRESH_BACKGROUND -> {
                                val path =
                                        intent.getStringExtra(WallpaperComm.EXTRA_BACKGROUND_PATH)
                                                ?: prefs.getString(
                                                        WallpaperComm.PREF_WALLPAPER_BG_PATH,
                                                        null
                                                )
                                loadBackgroundAsync(path)
                            }
                            WallpaperComm.ACTION_REFRESH_MODEL -> {
                                val folder =
                                        intent.getStringExtra(WallpaperComm.EXTRA_MODEL_FOLDER)
                                                ?: prefs.getString(
                                                        WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER,
                                                        null
                                                )
                                lastRequestedModelFolder = folder
                                renderer.setModelFolder(folder)
                            }
                        }
                    }
                }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            logger.info("Engine created, registering receivers & starting GL thread")
            val filter =
                    IntentFilter().apply {
                        addAction(WallpaperComm.ACTION_SEND_MESSAGE)
                        addAction(WallpaperComm.ACTION_REFRESH_BACKGROUND)
                        addAction(WallpaperComm.ACTION_REFRESH_MODEL)
                    }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION") registerReceiver(receiver, filter)
            }
            WallpaperChatCoordinator.addListener(bubbleListener)
            scope.launch {
                try {
                    WallpaperChatCoordinator.warmUp(this@Live2DWallpaperService.applicationContext)
                } catch (t: Throwable) {
                    logger.warn("Chat coordinator warm-up failed", t)
                }
            }
            startGlThread("engine init")
            logger.info("GL thread start requested")
            val initialModel = prefs.getString(WallpaperComm.PREF_WALLPAPER_MODEL_FOLDER, null)
            lastRequestedModelFolder = initialModel
            renderer.setModelFolder(initialModel)
            val persistedBg = prefs.getString(WallpaperComm.PREF_WALLPAPER_BG_PATH, null)
            loadBackgroundAsync(persistedBg, force = true)
        }

        override fun onDestroy() {
            super.onDestroy()
            logger.info("Engine destroyed, shutting down GL thread")
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {}
            WallpaperChatCoordinator.removeListener(bubbleListener)
            scope.cancel()
            scheduledRestartJob = null
            scheduledServiceResetJob = null
            val thread =
                    synchronized(glThreadGuard) {
                        val current = glThread
                        glThread = null
                        current
                    }
            thread?.shutdown()
            try {
                thread?.join(500)
            } catch (_: InterruptedException) {}
            renderer.release()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            logger.info("Visibility changed -> $visible")
            engineVisible = visible
            if (visible) {
                synchronized(glThreadGuard) { glThread }?.onResume()
                lastRequestedModelFolder?.let { renderer.setModelFolder(it) }
                loadBackgroundAsync(lastRequestedBackgroundPath, force = true)
            } else {
                synchronized(glThreadGuard) { glThread }?.onPause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            logger.info("Surface created: ${holder.surface?.isValid}")
            currentSurfaceHolder = holder
            synchronized(glThreadGuard) { glThread }?.onSurfaceCreated(holder)
            lastRequestedModelFolder?.let { renderer.setModelFolder(it) }
            loadBackgroundAsync(lastRequestedBackgroundPath, force = true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            logger.info("Surface changed -> $width x $height")
            currentSurfaceHolder = holder
            currentSurfaceWidth = width
            currentSurfaceHeight = height
            synchronized(glThreadGuard) { glThread }?.onSurfaceChanged(width, height)
            lastRequestedModelFolder?.let { renderer.setModelFolder(it) }
            loadBackgroundAsync(lastRequestedBackgroundPath, force = true)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            logger.info("Surface destroyed")
            currentSurfaceHolder = null
            currentSurfaceWidth = 0
            currentSurfaceHeight = 0
            synchronized(glThreadGuard) { glThread }?.onSurfaceDestroyed()
        }

        override fun onTouchEvent(event: MotionEvent) {
        logger.debug(
            "touch action=${MotionEvent.actionToString(event.actionMasked)} pointers=${event.pointerCount}",
            throttleMs = 200L,
            throttleKey = "touch_${event.actionMasked}"
        )
            gestureDispatcher.onTouchEvent(event)
        }

        private fun loadBackgroundAsync(path: String?, force: Boolean = false) {
            lastRequestedBackgroundPath = path
            if (path.isNullOrBlank()) {
                logger.info("Clearing wallpaper background (empty path)")
                lastAppliedBackgroundPath = null
                renderer.setBackgroundBitmap(null)
                return
            }
            if (!force && lastAppliedBackgroundPath == path) {
                logger.debug(
                        "Background unchanged ($path), skip reload",
                        throttleMs = 2_000L,
                        throttleKey = "bg_skip"
                )
                return
            }
            logger.info("Loading wallpaper background: $path (force=$force)")
            scope.launch {
                val bitmap = decodeSampledBitmap(path)
                if (bitmap != null) {
                    logger.info(
                            "Background decoded (${bitmap.width}x${bitmap.height}), applying"
                    )
                    renderer.setBackgroundBitmap(bitmap)
                    lastAppliedBackgroundPath = path
                } else {
                    logger.warn("Background decode failed for $path, reverting to default")
                    renderer.setBackgroundBitmap(null)
                    lastAppliedBackgroundPath = null
                }
            }
        }

        private fun decodeSampledBitmap(path: String): Bitmap? {
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                val maxDim = 2048
                var sample = 1
                while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
                    sample *= 2
                }
                logger.debug(
                        "Decoding background with inSampleSize=$sample (src=${opts.outWidth}x${opts.outHeight})"
                )
                val decodeOpts =
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                BitmapFactory.decodeFile(path, decodeOpts)
            } catch (e: Exception) {
                logger.error("decode background failed", e)
                null
            }
        }
    }
}

private class WallpaperGLThread(
        private val renderer: GLSurfaceView.Renderer,
        private val listener: RenderEventListener? = null
) : Thread("Live2DWallpaperGL") {
    companion object {
        private const val ERROR_BACKOFF_MS = 120L
    }

    private val logger = L2DLogger.module(LogModule.WALLPAPER)

    interface RenderEventListener {
        fun onRenderError(stage: RenderStage, throwable: Throwable? = null)
        fun onRenderSuccess()
    }

    enum class RenderStage {
        EGL_INIT,
        SURFACE_CREATED,
        SURFACE_CHANGED,
        DRAW_FRAME,
        SWAP_BUFFERS
    }

    private val lock = Object()
    private var eglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var holder: SurfaceHolder? = null
    private var running = true
    private var paused = false
    private var surfaceReady = false
    private var sizeDirty = false
    private var width = 0
    private var height = 0
    private var needsSurfaceCreatedCallback = false

    override fun run() {
        logger.info("GL thread starting EGL init")
        initEgl()
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) {
            logger.error("EGL init failed, aborting GL thread loop")
            signalError(RenderStage.EGL_INIT, RuntimeException("EGL init failed"))
            return
        }
        logger.info("EGL init success, entering render loop")
        while (true) {
            var shouldExit = false
            var shouldSkipFrame = false
            synchronized(lock) {
        while (running && !surfaceReady) {
            logger.debug(
                "Waiting (running=$running, surfaceReady=$surfaceReady, paused=$paused)",
                throttleMs = 2_000L,
                throttleKey = "gl_wait_state"
            )
                    lock.wait()
                }
                if (!running) {
                    shouldExit = true
                }
                shouldSkipFrame = paused
            }
            if (shouldExit) break
            if (shouldSkipFrame) {
                // 节能：不可见时不必渲染整帧，但保持循环以便处理恢复和资源销毁
                try {
                    sleep(40)
                } catch (_: InterruptedException) {}
                continue
            }
            if (!ensureSurface()) {
                logger.warn(
                        "ensureSurface failed, retrying",
                        throttleMs = 1_000L,
                        throttleKey = "ensure_surface"
                )
                sleep(16)
                continue
            }
            if (needsSurfaceCreatedCallback) {
                try {
                    logger.info("Dispatching renderer.onSurfaceCreated")
                    renderer.onSurfaceCreated(null, null)
                } catch (t: Throwable) {
                    logger.error("renderer.onSurfaceCreated crashed", t)
                    needsSurfaceCreatedCallback = true
                    destroySurface()
                    signalError(RenderStage.SURFACE_CREATED, t)
                    sleep(ERROR_BACKOFF_MS)
                    continue
                }
                needsSurfaceCreatedCallback = false
                sizeDirty = true
            }
            if (sizeDirty) {
                try {
                    logger.info(
                            "Dispatching renderer.onSurfaceChanged -> ${width}x${height}"
                    )
                    renderer.onSurfaceChanged(null, width, height)
                } catch (t: Throwable) {
                    logger.error("renderer.onSurfaceChanged crashed", t)
                    destroySurface()
                    signalError(RenderStage.SURFACE_CHANGED, t)
                    sleep(ERROR_BACKOFF_MS)
                    continue
                }
                sizeDirty = false
            }
            try {
                renderer.onDrawFrame(null)
            } catch (t: Throwable) {
                logger.error("renderer.onDrawFrame crashed", t)
                destroySurface()
                signalError(RenderStage.DRAW_FRAME, t)
                sleep(ERROR_BACKOFF_MS)
                continue
            }
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                val error = EGL14.eglGetError()
                logger.error("eglSwapBuffers failed: $error")
                signalError(
                        RenderStage.SWAP_BUFFERS,
                        RuntimeException("eglSwapBuffers failed: $error")
                )
            } else {
                signalSuccess()
            }
        }
        logger.info("Render loop exiting, cleaning up EGL")
        destroySurface()
        teardownEgl()
    }

    fun onSurfaceCreated(holder: SurfaceHolder) {
    logger.info("onSurfaceCreated holder=$holder")
        synchronized(lock) {
            this.holder = holder
            surfaceReady = true
            lock.notifyAll()
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
    logger.info("onSurfaceChanged width=$width height=$height")
        synchronized(lock) {
            this.width = width
            this.height = height
            sizeDirty = true
            lock.notifyAll()
        }
    }

    fun onSurfaceDestroyed() {
        logger.info("onSurfaceDestroyed")
        synchronized(lock) {
            surfaceReady = false
            lock.notifyAll()
        }
        destroySurface()
    }

    fun onPause() {
        logger.info("onPause")
        synchronized(lock) { paused = true }
    }

    fun onResume() {
        logger.info("onResume")
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }

    fun shutdown() {
        logger.info("shutdown requested")
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            logger.error("Unable to get EGL display: ${EGL14.eglGetError()}")
            signalError(RenderStage.EGL_INIT, RuntimeException("eglGetDisplay returned NO_DISPLAY"))
            return
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            logger.error("Unable to initialize EGL: ${EGL14.eglGetError()}")
            signalError(RenderStage.EGL_INIT, RuntimeException("eglInitialize failed"))
            return
        }
    logger.info("EGL initialized version=${version[0]}.${version[1]}")
        val attribs =
                intArrayOf(
                        EGL14.EGL_RED_SIZE,
                        8,
                        EGL14.EGL_GREEN_SIZE,
                        8,
                        EGL14.EGL_BLUE_SIZE,
                        8,
                        EGL14.EGL_ALPHA_SIZE,
                        8,
                        EGL14.EGL_DEPTH_SIZE,
                        16,
                        EGL14.EGL_RENDERABLE_TYPE,
                        EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_NONE
                )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)) {
            logger.error("Unable to choose EGL config: ${EGL14.eglGetError()}")
            signalError(RenderStage.EGL_INIT, RuntimeException("eglChooseConfig failed"))
            return
        }
        eglConfig = configs[0]
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext =
                EGL14.eglCreateContext(
                        eglDisplay,
                        eglConfig,
                        EGL14.EGL_NO_CONTEXT,
                        contextAttribs,
                        0
                )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            logger.error("Unable to create EGL context: ${EGL14.eglGetError()}")
            signalError(RenderStage.EGL_INIT, RuntimeException("eglCreateContext failed"))
        } else {
            logger.info("EGL context created")
        }
    }

    private fun ensureSurface(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return false
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                logger.error("eglMakeCurrent failed: ${EGL14.eglGetError()}")
                destroySurface()
            } else {
                return true
            }
        }
        val currentHolder = holder ?: return false
        val surface = currentHolder.surface ?: return false
        if (!surface.isValid) return false
        eglSurface =
                EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        eglConfig,
                        surface,
                        intArrayOf(EGL14.EGL_NONE),
                        0
                )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            logger.error("Unable to create window surface: ${EGL14.eglGetError()}")
            return false
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            logger.error("eglMakeCurrent after create failed: ${EGL14.eglGetError()}")
            destroySurface()
            return false
        }
        logger.info("Window surface created & made current")
        needsSurfaceCreatedCallback = true
        return true
    }

    private fun destroySurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            logger.info("Destroying EGL surface")
            EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun teardownEgl() {
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            logger.info("Destroying EGL context")
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            logger.info("Terminating EGL display")
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun signalError(stage: RenderStage, throwable: Throwable?) {
        try {
            listener?.onRenderError(stage, throwable)
        } catch (t: Throwable) {
            logger.warn("Error listener callback failed", t)
        }
    }

    private fun signalSuccess() {
        try {
            listener?.onRenderSuccess()
        } catch (t: Throwable) {
            logger.warn("Success listener callback failed", t)
        }
    }
}
