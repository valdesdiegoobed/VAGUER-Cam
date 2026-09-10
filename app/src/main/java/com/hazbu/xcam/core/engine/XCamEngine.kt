package com.hazbu.xcam.core.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider

@UnstableApi
class XCamEngine(
    private val contextProvider: () -> Context?,
    private val settingsProvider: () -> SettingsManager,
    private val surfaceManager: SurfaceManager,
    private val mediaEngine: MediaEngine,
    private val surfaceProvider: SurfaceProvider,
    private val previewRotationProvider: (Context) -> Int,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())

    private var lastST: SurfaceTexture? = null
    private var lastModernSurface: Surface? = null
    private var lastOutputSurface: Surface? = null
    private var lastInjectedGen = -1
    private var lastInjectedSurfaceId = -1L

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")
    private fun logPipe(msg: String) = log("PIPELINE", msg)

    fun isPlaying() = mediaEngine.isPlaying
    fun getCurrentPosition() = mediaEngine.currentPosition

    fun stop() {
        uiHandler.removeCallbacksAndMessages(null)
        mediaEngine.stop()
        surfaceProvider.release()
        lastST = null
        lastModernSurface = null
        lastOutputSurface = null
        lastInjectedSurfaceId = -1L
    }

    fun handleCamera1Preview(st: SurfaceTexture) {
        val surface = Surface(st)
        if (!surfaceManager.isPreviewSurface(surface)) {
            surface.release()
            return
        }

        if (
            st == lastST && mediaEngine.isPlaying &&
            lastInjectedGen == surfaceManager.sessionGeneration
        ) {
            surface.release()
            return
        }

        val context = contextProvider() ?: run {
            surface.release()
            return
        }
        val settings = settingsProvider()
        settings.refreshSettings(context)
        val path = settings.mediaPath ?: run {
            surface.release()
            return
        }

        lastST = st
        lastOutputSurface = surface
        lastInjectedGen = surfaceManager.sessionGeneration
        logPipe("Legacy Hook: Injecting to SurfaceTexture")
        playWithSettings(context, path, surface, "Legacy")
    }

    fun handleModernPreview(surface: Surface) {
        val currentGen = surfaceManager.sessionGeneration
        if (
            surface == lastModernSurface && mediaEngine.isPlaying &&
            lastInjectedGen == currentGen
        ) return
        lastModernSurface = surface
        lastOutputSurface = surface
        uiHandler.post { processInjection(surface) }
    }

    fun handleSurfaceViewPreview(holder: SurfaceHolder) {
        uiHandler.post {
            if (surfaceManager.isPreviewSurface(holder.surface)) {
                processInjection(holder.surface)
            }
        }
    }

    private fun processInjection(surface: Surface) {
        val context = contextProvider() ?: return
        val settings = settingsProvider()
        settings.refreshSettings(context)
        val path = settings.mediaPath ?: return
        lastOutputSurface = surface
        injectToSurface(surface, context, path)
    }

    fun injectToSurface(surface: Surface, context: Context, path: String) {
        synchronized(this) {
            if (!surface.isValid) return

            val id = com.hazbu.xcam.utils.SystemUtils.getSurfaceId(surface)
            val currentGen = surfaceManager.sessionGeneration
            if (
                id == lastInjectedSurfaceId && mediaEngine.isPlaying &&
                currentGen == lastInjectedGen
            ) return

            logPipe("Injection: ID=$id Gen=$currentGen")
            mediaEngine.stop()
            lastInjectedGen = currentGen
            lastInjectedSurfaceId = id
            playWithSettings(context, path, surface, "Engine")
        }
    }

    fun refreshActiveOutput() {
        uiHandler.removeCallbacks(refreshOutputRunnable)
        uiHandler.postDelayed(refreshOutputRunnable, 120L)
    }

    private val refreshOutputRunnable = Runnable {
        val context = contextProvider() ?: return@Runnable
        val surface = lastOutputSurface ?: return@Runnable
        if (!surface.isValid) return@Runnable

        val settings = settingsProvider()
        settings.refreshSettings(context)
        val path = settings.mediaPath ?: return@Runnable

        logPipe(
            "Live settings refresh: rot=${settings.rotationAngle} " +
                "scale=${"%.2f".format(settings.scaleX)}x${"%.2f".format(settings.scaleY)} " +
                "offset=${"%.2f".format(settings.offsetX)},${"%.2f".format(settings.offsetY)}",
        )
        mediaEngine.stop()
        lastInjectedSurfaceId = com.hazbu.xcam.utils.SystemUtils.getSurfaceId(surface)
        playWithSettings(context, path, surface, "LiveRefresh")
    }

    private fun playWithSettings(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
    ) {
        val settings = settingsProvider()
        val previewCompensation = previewRotationProvider(context)
        logPipe(
            "Output transform: editorRot=${settings.rotationAngle} " +
                "cameraComp=$previewCompensation",
        )
        mediaEngine.play(
            context = context,
            path = path,
            surface = surface,
            tag = tag,
            isMirrored = settings.isMirrored,
            rotationAngle = settings.rotationAngle,
            scaleX = settings.scaleX,
            scaleY = settings.scaleY,
            offsetX = settings.offsetX,
            offsetY = settings.offsetY,
            fitMode = settings.fitMode,
            brightness = settings.brightness,
            contrast = settings.contrast,
            saturation = settings.saturation,
            outputRotationCompensation = previewCompensation,
        )
    }

    fun getDummySurface(): Surface = surfaceProvider.getDummySurface()
}
