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
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())

    private var lastST: SurfaceTexture? = null
    private var lastModernSurface: Surface? = null
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
        ) return

        lastST = st
        lastInjectedGen = surfaceManager.sessionGeneration
        val path = settingsProvider().mediaPath ?: return
        val context = contextProvider() ?: return
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
        val path = settingsProvider().mediaPath ?: return
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

    private fun playWithSettings(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
    ) {
        val settings = settingsProvider()
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
        )
    }

    fun getDummySurface(): Surface = surfaceProvider.getDummySurface()
}
