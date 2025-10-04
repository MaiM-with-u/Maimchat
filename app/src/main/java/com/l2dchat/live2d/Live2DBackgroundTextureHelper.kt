package com.l2dchat.live2d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.l2dchat.wallpaper.WallpaperComm
import com.live2d.demo.LAppDefine
import com.live2d.demo.full.LAppDelegate
import com.live2d.demo.full.LAppTextureManager
import com.live2d.demo.full.LAppView
import kotlin.math.abs
import kotlin.math.roundToInt

object Live2DBackgroundTextureHelper {
    private const val TAG = "Live2DBGHelper"
    const val BACKGROUND_TEXTURE_KEY = "user_background_texture"

    fun applyBackgroundTexture(
            textureManager: LAppTextureManager?,
            view: LAppView?,
            path: String?
    ) {
        if (textureManager == null || view == null) {
            Log.w(TAG, "纹理管理器或视图为空，无法应用背景")
            return
        }
        if (path.isNullOrBlank()) {
            Log.d(TAG, "应用默认背景纹理")
            setDefaultBackground(textureManager, view)
            return
        }
        try {
            val options =
                    BitmapFactory.Options().apply {
                        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                    }
            val bitmap = BitmapFactory.decodeFile(path, options)
            if (bitmap != null) {
                applyBackgroundTexture(textureManager, view, bitmap)
            } else {
                Log.w(TAG, "无法解码背景: $path，使用默认背景")
                setDefaultBackground(textureManager, view)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "加载背景纹理失败 path=$path", t)
            setDefaultBackground(textureManager, view)
        }
    }

    fun applyBackgroundTexture(
            textureManager: LAppTextureManager?,
            view: LAppView?,
            bitmap: Bitmap?
    ) {
        if (textureManager == null || view == null) {
            Log.w(TAG, "纹理管理器或视图为空，无法应用背景位图")
            bitmap?.recycle()
            return
        }
        if (bitmap == null) {
            Log.d(TAG, "位图为空，切换默认背景")
            setDefaultBackground(textureManager, view)
            return
        }
        var original: Bitmap? = bitmap
        var adjusted: Bitmap? = null
        try {
            var working = adjustBitmapForView(bitmap)
            if (working !== bitmap) {
                adjusted = working
            }

            val textureInfo =
                    textureManager.createTextureFromBitmap(working, BACKGROUND_TEXTURE_KEY)
            view.setBackgroundTextureInfo(textureInfo)
            Log.d(
                    TAG,
                    "应用自定义背景纹理成功 texId=${textureInfo.id} size=${textureInfo.width}x${textureInfo.height}"
            )

            if (adjusted !== null &&
                            original !== null &&
                            adjusted !== original &&
                            !original.isRecycled
            ) {
                original.recycle()
            }
            original = null
            adjusted = null
        } catch (t: Throwable) {
            Log.e(TAG, "应用自定义背景失败", t)
            adjusted?.let { if (!it.isRecycled) it.recycle() }
            original?.let { if (!it.isRecycled) it.recycle() }
            setDefaultBackground(textureManager, view)
        }
    }

    fun loadPersistedBackgroundPath(context: Context): String? {
        return try {
            context.applicationContext
                    .getSharedPreferences(WallpaperComm.PREF_WALLPAPER, Context.MODE_PRIVATE)
                    .getString(WallpaperComm.PREF_WALLPAPER_BG_PATH, null)
        } catch (t: Throwable) {
            Log.w(TAG, "读取背景偏好失败", t)
            null
        }
    }

    private fun setDefaultBackground(textureManager: LAppTextureManager, view: LAppView) {
        try {
            textureManager.deleteTexture(BACKGROUND_TEXTURE_KEY)
        } catch (_: Throwable) {}
        val defaultPath = LAppDefine.ResourcePath.BACK_IMAGE.getPath()
        val textureInfo =
                try {
                    textureManager.createTextureFromPngFile(defaultPath)
                } catch (t: Throwable) {
                    Log.e(TAG, "加载默认背景失败: $defaultPath", t)
                    null
                }
        view.setBackgroundTextureInfo(textureInfo)
    }
    private fun adjustBitmapForView(bitmap: Bitmap): Bitmap {
        val delegate = LAppDelegate.getInstance()
        val targetWidth = delegate?.windowWidth ?: 0
        val targetHeight = delegate?.windowHeight ?: 0
        if (targetWidth <= 0 || targetHeight <= 0) {
            return bitmap
        }
        val cropped = cropBitmapToAspect(bitmap, targetWidth, targetHeight)
        if (cropped !== bitmap) {
            Log.d(
                    TAG,
                    "裁剪背景以匹配视图比例: src=${bitmap.width}x${bitmap.height} -> dst=${cropped.width}x${cropped.height}"
            )
        }
        return cropped
    }

    private fun cropBitmapToAspect(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (targetWidth <= 0 || targetHeight <= 0) return bitmap
        val viewAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        if (abs(bitmapAspect - viewAspect) < 0.01f) {
            return bitmap
        }

        return if (bitmapAspect > viewAspect) {
            val desiredWidth = (bitmap.height * viewAspect).roundToInt().coerceIn(1, bitmap.width)
            val offsetX = ((bitmap.width - desiredWidth) / 2f).roundToInt().coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, offsetX, 0, desiredWidth, bitmap.height)
        } else {
            val desiredHeight = (bitmap.width / viewAspect).roundToInt().coerceIn(1, bitmap.height)
            val offsetY = ((bitmap.height - desiredHeight) / 2f).roundToInt().coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, 0, offsetY, bitmap.width, desiredHeight)
        }
    }
}
