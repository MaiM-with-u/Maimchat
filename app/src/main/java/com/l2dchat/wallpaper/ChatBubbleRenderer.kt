package com.l2dchat.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 在 OpenGL 上渲染聊天气泡的辅助渲染器。 */
class ChatBubbleRenderer(private val context: Context) {
    private data class BubbleRequest(
            val message: String,
            val fromUser: Boolean,
            val createdAt: Long
    )

    private data class BubbleInstance(
            val textureId: Int,
            val widthPx: Int,
            val heightPx: Int,
            val fromUser: Boolean,
            val createdAt: Long,
            val expiresAt: Long
    ) {
        var xCenterPx: Float = 0f
        var yCenterPx: Float = 0f
    }

    companion object {
        private const val TAG = "ChatBubbleRenderer"
        private const val MAX_BUBBLES = 3
        private const val LIFETIME_MS = 6_000L
        private const val FADE_IN_MS = 220L
        private const val FADE_OUT_MS = 560L
        private const val SIDE_MARGIN_DP = 16f
        private const val BOTTOM_MARGIN_DP = 28f
        private const val DOCK_SAFE_MARGIN_DP = 56f
        private const val BUBBLE_SPACING_DP = 12f
        private const val MAX_WIDTH_RATIO = 0.65f
        private const val TEXT_SIZE_SP = 15f
        private const val VERTEX_SHADER =
                """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER =
                """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """

        private fun createProgram(vertexShader: String, fragmentShader: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader)
            if (vertex == 0) return 0
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)
            if (fragment == 0) {
                GLES20.glDeleteShader(vertex)
                return 0
            }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                GLES20.glDeleteProgram(program)
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                return 0
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return program
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    private val pending = ConcurrentLinkedQueue<BubbleRequest>()
    private val active = ArrayList<BubbleInstance>()

    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var alphaHandle = 0
    private var textureHandle = 0

    private val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val texCoordBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val textPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = spToPx(TEXT_SIZE_SP)
            }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun onSurfaceCreated() {
        destroyProgram()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) {
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            alphaHandle = GLES20.glGetUniformLocation(program, "uAlpha")
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        }
        clearAllBubbles()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
    }

    fun onSurfaceDestroyed() {
        clearAllBubbles()
        destroyProgram()
    }

    fun enqueueBubble(message: String, fromUser: Boolean) {
        pending.offer(BubbleRequest(message, fromUser, SystemClock.elapsedRealtime()))
    }

    fun render() {
        if (program == 0) return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val now = SystemClock.elapsedRealtime()
        drainPending(now)
        if (active.isEmpty()) return

        cleanupExpired(now)
        if (active.isEmpty()) return

        layoutBubbles()

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        for (bubble in active) {
            val alpha = computeAlpha(bubble, now)
            if (alpha <= 0f) continue
            drawBubble(bubble, alpha)
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glUseProgram(0)
    }

    fun release() {
        clearAllBubbles()
        destroyProgram()
    }

    private fun drainPending(now: Long) {
        var request = pending.poll()
        while (request != null) {
            if (viewportWidth <= 0 || viewportHeight <= 0) {
                // 视口尚未准备好，稍后再处理
                pending.offer(request)
                return
            }
            createBubbleTexture(request, now)
            request = pending.poll()
        }
    }

    private fun createBubbleTexture(request: BubbleRequest, now: Long) {
        val maxBubbleWidth = max(1, (viewportWidth * MAX_WIDTH_RATIO).roundToInt())
        val bitmap =
                BubbleBitmapFactory.createBubbleBitmap(
                        context,
                        request.message,
                        request.fromUser,
                        maxBubbleWidth,
                        textPaint,
                        bubblePaint
                )
        val bubbleWidth = bitmap.width
        val bubbleHeight = bitmap.height
        val textureId = generateTexture(bitmap)
        bitmap.recycle()

        val bubble =
                BubbleInstance(
                        textureId = textureId,
                        widthPx = bubbleWidth,
                        heightPx = bubbleHeight,
                        fromUser = request.fromUser,
                        createdAt = now,
                        expiresAt = now + LIFETIME_MS
                )
        synchronized(active) {
            active.add(bubble)
            if (active.size > MAX_BUBBLES) {
                val removed = active.removeAt(0)
                deleteTexture(removed.textureId)
            }
        }
    }

    private fun layoutBubbles() {
        val marginSide = dpToPx(SIDE_MARGIN_DP)
        val bottomMargin = dpToPx(BOTTOM_MARGIN_DP + DOCK_SAFE_MARGIN_DP)
        val spacing = dpToPx(BUBBLE_SPACING_DP)

        var cursorY = viewportHeight - bottomMargin
        synchronized(active) {
            for (i in active.indices.reversed()) {
                val bubble = active[i]
                val centerY = cursorY - bubble.heightPx / 2f
                bubble.yCenterPx = centerY
                bubble.xCenterPx =
                        if (bubble.fromUser) {
                            viewportWidth - marginSide - bubble.widthPx / 2f
                        } else {
                            marginSide + bubble.widthPx / 2f
                        }
                cursorY = centerY - bubble.heightPx / 2f - spacing
            }
        }
    }

    private fun computeAlpha(bubble: BubbleInstance, now: Long): Float {
        val age = now - bubble.createdAt
        if (age < 0L) return 0f
        val timeLeft = bubble.expiresAt - now
        if (timeLeft <= 0L) return 0f

        val fadeIn = if (age < FADE_IN_MS) age / FADE_IN_MS.toFloat() else 1f
        val fadeOut = if (timeLeft < FADE_OUT_MS) timeLeft / FADE_OUT_MS.toFloat() else 1f
        return max(0f, min(1f, fadeIn * fadeOut))
    }

    private fun drawBubble(bubble: BubbleInstance, alpha: Float) {
        val halfWidth = bubble.widthPx / 2f
        val halfHeight = bubble.heightPx / 2f

        val left = (bubble.xCenterPx - halfWidth) / viewportWidth * 2f - 1f
        val right = (bubble.xCenterPx + halfWidth) / viewportWidth * 2f - 1f
        val top = 1f - (bubble.yCenterPx - halfHeight) / viewportHeight * 2f
        val bottom = 1f - (bubble.yCenterPx + halfHeight) / viewportHeight * 2f

        val vertices = floatArrayOf(left, top, right, top, left, bottom, right, bottom)
        vertexBuffer.clear()
        vertexBuffer.put(vertices)
        vertexBuffer.position(0)

        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        texCoordBuffer.clear()
        texCoordBuffer.put(texCoords)
        texCoordBuffer.position(0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bubble.textureId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniform1f(alphaHandle, alpha)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun cleanupExpired(now: Long) {
        synchronized(active) {
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val bubble = iterator.next()
                if (now >= bubble.expiresAt) {
                    deleteTexture(bubble.textureId)
                    iterator.remove()
                }
            }
        }
    }

    private fun clearAllBubbles() {
        synchronized(active) {
            active.forEach { deleteTexture(it.textureId) }
            active.clear()
        }
        pending.clear()
    }

    private fun generateTexture(bitmap: Bitmap): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val id = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return id
    }

    private fun deleteTexture(id: Int) {
        if (id != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        }
    }

    private fun destroyProgram() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density
    private fun spToPx(sp: Float): Float =
            TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    sp,
                    context.resources.displayMetrics
            )
}

private object BubbleBitmapFactory {
    private const val RADIUS_DP = 18f
    private const val PADDING_H_DP = 14f
    private const val PADDING_V_DP = 10f
    private const val TAIL_WIDTH_DP = 12f
    private const val TAIL_HEIGHT_DP = 10f

    val userBubbleColor = Color.parseColor("#FF4A90E2")
    val peerBubbleColor = Color.parseColor("#CC222222")
    val userTailColor = userBubbleColor
    val peerTailColor = peerBubbleColor

    fun createBubbleBitmap(
            context: Context,
            message: String,
            fromUser: Boolean,
            maxWidth: Int,
            textPaintTemplate: TextPaint,
            bubblePaint: Paint
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val paddingH = (PADDING_H_DP * density).roundToInt()
        val paddingV = (PADDING_V_DP * density).roundToInt()
        val tailWidth = (TAIL_WIDTH_DP * density).roundToInt()
        val tailHeight = (TAIL_HEIGHT_DP * density).roundToInt()
        val cornerRadius = RADIUS_DP * density

        val textPaint = TextPaint(textPaintTemplate)
        textPaint.color = Color.WHITE

        val layout =
                StaticLayout.Builder.obtain(message, 0, message.length, textPaint, max(1, maxWidth))
                        .setAlignment(
                                if (fromUser) Layout.Alignment.ALIGN_OPPOSITE
                                else Layout.Alignment.ALIGN_NORMAL
                        )
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.15f)
                        .build()

        val bubbleWidth = layout.width + paddingH * 2
        val bubbleHeight = layout.height + paddingV * 2 + tailHeight

        val bitmap = Bitmap.createBitmap(bubbleWidth, bubbleHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = if (fromUser) userBubbleColor else peerBubbleColor
        bubblePaint.style = Paint.Style.FILL
        bubblePaint.color = bgColor

        val rect = RectF(0f, 0f, bubbleWidth.toFloat(), (bubbleHeight - tailHeight).toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bubblePaint)

        val tailPath =
                Path().apply {
                    val baseY = (bubbleHeight - tailHeight).toFloat()
                    if (fromUser) {
                        moveTo(bubbleWidth - paddingH.toFloat() - tailWidth, baseY)
                        lineTo(bubbleWidth - paddingH.toFloat(), baseY)
                        lineTo(
                                bubbleWidth - paddingH.toFloat() - tailWidth / 2f,
                                bubbleHeight.toFloat()
                        )
                    } else {
                        moveTo(paddingH.toFloat() + tailWidth, baseY)
                        lineTo(paddingH.toFloat(), baseY)
                        lineTo(paddingH.toFloat() + tailWidth / 2f, bubbleHeight.toFloat())
                    }
                    close()
                }
        canvas.drawPath(tailPath, bubblePaint)

        canvas.save()
        val textStartX = paddingH.toFloat()
        val textStartY = paddingV.toFloat()
        canvas.translate(textStartX, textStartY)
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
