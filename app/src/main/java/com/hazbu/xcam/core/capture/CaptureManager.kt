package com.hazbu.xcam.core.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_WIDTH
import com.hazbu.xcam.data.Constants.STREAM_FRAME_INTERVAL_MS
import java.util.concurrent.Executors

/**
 * Manages frame extraction for still captures and video/YUV streaming.
 *
 * Every output path receives the same editor transform. This prevents a target
 * app that reads ImageReader/YUV frames from silently falling back to the
 * unzoomed source while the Surface preview is transformed.
 */
class CaptureManager(
    private val contextProvider: () -> Context?,
    private val refreshSettingsAction: (Context) -> Unit,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private val streamFrameIntervalMs = STREAM_FRAME_INTERVAL_MS

    @Volatile
    var isCapturing = false
        private set

    @Volatile
    private var cachedCaptureFrame: ByteArray? = null

    @Volatile
    private var cachedStreamFrame: ByteArray? = null

    private var lastCapturePulseTime = 0L
    private var captureTimeMs = 0

    private var streamKey: String? = null
    private var streamStartedAtMs = 0L
    private var lastStreamRequestAtMs = 0L

    @Volatile
    private var streamExtractionRunning = false

    private fun log(msg: String) = logAction("[CAPTURE] $msg")

    fun triggerCaptureState(currentPositionProvider: () -> Int) {
        val now = System.currentTimeMillis()
        if ((now - lastCapturePulseTime) < 2000) return
        lastCapturePulseTime = now

        captureTimeMs =
            try {
                currentPositionProvider()
            } catch (_: Throwable) {
                0
            }
        log("[*] Pulse detected! Target Frame Time: $captureTimeMs ms")

        isCapturing = true
        cachedCaptureFrame = null

        contextProvider()?.let { refreshSettingsAction(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed(
            {
                isCapturing = false
                uiHandler.postDelayed({ cachedCaptureFrame = null }, 5000)
            },
            3000,
        )
    }

    private fun performExtraction(
        context: Context,
        path: String,
        width: Int,
        height: Int,
        rotation: Int,
        mirrored: Boolean,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        fitMode: String,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        outputRotationCompensation: Int = 0,
        timeMs: Int,
        setIgnoringHooks: (Boolean) -> Unit,
    ): ByteArray? {
        return try {
            setIgnoringHooks(true)
            XCamCapture.createJpeg(
                context = context,
                path = path,
                targetW = width.coerceAtLeast(DEFAULT_CAPTURE_WIDTH),
                targetH = height.coerceAtLeast(DEFAULT_CAPTURE_HEIGHT),
                rotation = rotation,
                mirrored = mirrored,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = offsetX,
                offsetY = offsetY,
                fitMode = fitMode,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                outputRotationCompensation = outputRotationCompensation,
                timeMs = timeMs,
                printLog = logAction,
            )
        } catch (e: Throwable) {
            log("Extraction failed: ${e.message}")
            null
        } finally {
            setIgnoringHooks(false)
        }
    }

    fun handleCapture(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        fitMode: String,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit,
    ): ByteArray? {
        if (isIgnoringHooks() || path == null) return null
        val context = contextProvider() ?: return null

        return synchronized(this) {
            cachedCaptureFrame
                ?: performExtraction(
                    context = context,
                    path = path,
                    width = width,
                    height = height,
                    rotation = rotationAngle,
                    mirrored = isMirrored,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    fitMode = fitMode,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    outputRotationCompensation = 0,
                    timeMs = captureTimeMs,
                    setIgnoringHooks = setIgnoringHooks,
                ).also {
                    cachedCaptureFrame = it
                    if (it != null) log("[+] Capture extraction successful")
                }
        }
    }

    fun handleStreamFrame(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        fitMode: String,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        outputRotationCompensation: Int = 0,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit,
    ): ByteArray? {
        val context = contextProvider() ?: return null
        val actualPath = path ?: return null

        if (!actualPath.lowercase().endsWith(".mp4")) {
            return handleCapture(
                path = path,
                width = width,
                height = height,
                rotationAngle = rotationAngle,
                isMirrored = isMirrored,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = offsetX,
                offsetY = offsetY,
                fitMode = fitMode,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                isIgnoringHooks = isIgnoringHooks,
                setIgnoringHooks = setIgnoringHooks,
            )
        }

        val now = SystemClock.elapsedRealtime()
        val key =
            buildString {
                append(actualPath)
                append('|').append(width)
                append('|').append(height)
                append('|').append(rotationAngle)
                append('|').append(isMirrored)
                append('|').append(scaleX)
                append('|').append(scaleY)
                append('|').append(offsetX)
                append('|').append(offsetY)
                append('|').append(fitMode)
                append('|').append(brightness)
                append('|').append(contrast)
                append('|').append(saturation)
                append('|').append(outputRotationCompensation)
            }

        synchronized(this) {
            if (streamKey != key) {
                streamKey = key
                cachedStreamFrame = null
                streamStartedAtMs = now
                lastStreamRequestAtMs = 0L
                streamExtractionRunning = false
            }

            if (
                !streamExtractionRunning &&
                now - lastStreamRequestAtMs >= streamFrameIntervalMs
            ) {
                streamExtractionRunning = true
                lastStreamRequestAtMs = now
                val frameTimeMs = (now - streamStartedAtMs).toInt().coerceAtLeast(0)

                streamExecutor.execute {
                    performExtraction(
                        context = context,
                        path = actualPath,
                        width = width,
                        height = height,
                        rotation = rotationAngle,
                        mirrored = isMirrored,
                        scaleX = scaleX,
                        scaleY = scaleY,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        fitMode = fitMode,
                        brightness = brightness,
                        contrast = contrast,
                        saturation = saturation,
                        outputRotationCompensation = outputRotationCompensation,
                        timeMs = frameTimeMs,
                        setIgnoringHooks = setIgnoringHooks,
                    )?.let {
                        cachedStreamFrame = it
                    }
                    synchronized(this) {
                        streamExtractionRunning = false
                    }
                }
            }
        }

        return cachedStreamFrame
    }
}
