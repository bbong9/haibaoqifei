package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/** Owns only the Surface wrapper; returning true lets TextureView release its texture. */
internal class ClusterVideoTexture(context: Context, private val onPresented: () -> Unit = {},
    private val onSurface: (Surface?) -> Unit) :
    TextureView(context), java.io.Closeable {
    private var output: Surface? = null
    private var contentRect: RectF? = null
    private val pictureBinding = CarPlayPicture.Binding(this)
    init {
        isOpaque = false
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                releaseOutput()
                texture.setDefaultBufferSize(1920, 720)
                output = Surface(texture).also(onSurface)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { applyTransform() }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { onPresented() }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releaseOutput()
                return true
            }
        }
    }
    fun setContentRect(rect: RectF?) {
        contentRect = rect?.let(::RectF)
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    private fun applyTransform() {
        val rect = contentRect
        val matrix = Matrix()
        if (rect != null && width > 0 && height > 0 && rect.width() > 0f && rect.height() > 0f) {
            val scaleX = 1f / rect.width()
            val scaleY = 1f / rect.height()
            matrix.setScale(scaleX, scaleY)
            matrix.postTranslate(-rect.left * width * scaleX, -rect.top * height * scaleY)
        }
        setTransform(matrix)
    }
    private fun releaseOutput() {
        if (output == null) return
        onSurface(null)
        output?.release()
        output = null
    }
    override fun close() {
        pictureBinding.close()
        surfaceTextureListener = null
        releaseOutput()
    }
}
