package com.idk.frameinterp

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.math.max
import kotlin.math.roundToInt

class FrameInterpService : Service() {

    private var mediaProjection: MediaProjection? = null
    private val interpolator = OpticalFlowInterpolator()
    private val scope = CoroutineScope(Dispatchers.Default)
    private var overlayContainer: FrameLayout? = null
    private var overlayImageView: ImageView? = null
    private var statsView: FrameStatsView? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentFrame: Bitmap? = null
    private var realFrameCount = 0L
    private var generatedFrameCount = 0L
    private var totalFrameCount = 0L

    companion object {
        var instance: FrameInterpService? = null
        var mediaProjectionCode = 0
        var mediaProjectionData: Intent? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaProjectionCode != 0 && mediaProjectionData != null) {
            startInterpolation()
        }
        return START_STICKY
    }

    private fun startInterpolation() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(mediaProjectionCode, mediaProjectionData!!)

        addOverlay()

        val metrics = resources.displayMetrics
        val captureWidth = max(480, metrics.widthPixels / 2)
        val captureHeight = max(720, metrics.heightPixels / 2)

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val bitmap = imageToBitmap(image)
                        handleCapturedFrame(bitmap)
                    } finally {
                        image.close()
                    }
                }
            }, mainHandler)
        }

        mediaProjection?.createVirtualDisplay(
            "FrameInterpDisplay",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )?.let { display ->
            virtualDisplay = display
        }
    }

    private fun handleCapturedFrame(bitmap: Bitmap) {
        val scaledBitmap = bitmap.scaleForInterpolation()
        bitmap.recycle()

        realFrameCount += 1
        val previousFrame = currentFrame
        if (previousFrame != null) {
            val generatedBitmap = interpolator.interpolate(previousFrame, scaledBitmap, 0.5f)
            generatedFrameCount += 1
            totalFrameCount += 1
            overlayImageView?.setImageBitmap(generatedBitmap)
            previousFrame.recycle()
        } else {
            overlayImageView?.setImageBitmap(scaledBitmap)
        }

        currentFrame = scaledBitmap
        statsView?.update(realFrameCount, generatedFrameCount, totalFrameCount)
    }

    private fun addOverlay() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        overlayImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.95f
        }
        statsView = FrameStatsView(this)

        overlayContainer?.addView(overlayImageView)
        overlayContainer?.addView(statsView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayContainer, params)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun Bitmap.scaleForInterpolation(): Bitmap {
        val width = max(1, this.width / 2)
        val height = max(1, this.height / 2)
        return Bitmap.createScaledBitmap(this, width, height, true)
    }

    private fun buildNotification(): Notification {
        val channelId = "frame_interp_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Frame Interpolation",
                NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Frame Interpolation running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mediaProjection?.stop()
        mediaProjection = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        interpolator.release()
        overlayContainer?.let {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(it)
        }
        currentFrame?.recycle()
        currentFrame = null
        scope.cancel()
    }

    private class FrameStatsView(context: Context) : View(context) {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
        }
        private val barPaints = listOf(
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
        )
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
        }
        private var realFrames = 0L
        private var generatedFrames = 0L
        private var totalFrames = 0L

        fun update(real: Long, generated: Long, total: Long) {
            realFrames = real
            generatedFrames = generated
            totalFrames = total
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            canvas.drawRoundRect(RectF(12f, 12f, width - 12f, height - 12f), 18f, 18f, backgroundPaint)

            val maxValue = max(1f, maxOf(realFrames.toFloat(), generatedFrames.toFloat(), totalFrames.toFloat()))
            val barWidth = (width - 80f) / 3f
            val maxBarHeight = height - 96f
            val baseY = height - 36f

            val values = listOf(
                "Real" to realFrames,
                "Generated" to generatedFrames,
                "Total" to totalFrames
            )

            values.forEachIndexed { index, (label, value) ->
                val left = 24f + index * barWidth
                val ratio = (value.toFloat() / maxValue).coerceAtLeast(0.1f)
                val barHeight = maxBarHeight * ratio
                val top = baseY - barHeight
                canvas.drawRect(left, top, left + barWidth - 20f, baseY, barPaints[index])
                canvas.drawText(label, left + 8f, baseY + 24f, labelPaint)
                canvas.drawText(value.toString(), left + 8f, top - 8f, labelPaint)
            }
        }
    }
}
