package com.hazbu.xcam.core.engine

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import com.hazbu.xcam.data.Constants

/**
 * Handles Media3/ExoPlayer lifecycle and the visual output sent to the virtual
 * camera surface. VAGUER Cam keeps xCam's looping playback and adds a desktop-
 * style transform stage plus lightweight color controls.
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile private var isBusy = false
    @Volatile private var isPlayingInternal = false
    @Volatile private var currentPositionInternal = 0L

    @Volatile var videoWidth = 0
        private set
    @Volatile var videoHeight = 0
        private set

    val isPlaying: Boolean get() = isPlayingInternal
    val currentPosition: Long
        get() = if (Looper.myLooper() == Looper.getMainLooper()) {
            player?.currentPosition ?: 0L
        } else {
            currentPositionInternal
        }

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")

    fun stop() {
        mainHandler.post {
            isBusy = true
            try {
                player?.apply {
                    stop()
                    clearVideoSurface()
                    release()
                }
            } catch (e: Throwable) {
                log("MEDIA-ENGINE", "Stop failed: ${e.message}")
            } finally {
                player = null
                isPlayingInternal = false
                currentPositionInternal = 0L
                videoWidth = 0
                videoHeight = 0
                isBusy = false
            }
        }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        fitMode: String = Constants.FIT_MODE_FIT,
        brightness: Float = 0f,
        contrast: Float = 0f,
        saturation: Float = 0f,
        onPrepared: ((ExoPlayer?) -> Unit)? = null,
    ) {
        mainHandler.post {
            if (isBusy) return@post
            isBusy = true

            try {
                player?.apply {
                    stop()
                    clearVideoSurface()
                    release()
                }
            } catch (_: Throwable) {
            }

            try {
                val uri = path.toUri()
                log(tag, "Loading media: $path")

                val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(
                    context.applicationContext,
                ).setExtensionRendererMode(
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                )

                val exoPlayer = ExoPlayer.Builder(
                    context.applicationContext,
                    renderersFactory,
                ).build()
                player = exoPlayer

                val effects = mutableListOf<Effect>()

                val presentationLayout = when (fitMode) {
                    Constants.FIT_MODE_FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    Constants.FIT_MODE_STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                    else -> Presentation.LAYOUT_SCALE_TO_FIT
                }
                effects.add(Presentation.createForAspectRatio(1f, presentationLayout))

                if (rotationAngle % 360 != 0) {
                    effects.add(
                        ScaleAndRotateTransformation.Builder()
                            .setRotationDegrees(rotationAngle.toFloat())
                            .build(),
                    )
                }

                if (
                    isMirrored ||
                    kotlin.math.abs(scaleX - 1f) > 0.001f ||
                    kotlin.math.abs(scaleY - 1f) > 0.001f ||
                    kotlin.math.abs(offsetX) > 0.001f ||
                    kotlin.math.abs(offsetY) > 0.001f
                ) {
                    effects.add(
                        UserTransformEffect(
                            scaleX = scaleX.coerceIn(0.25f, 4f),
                            scaleY = scaleY.coerceIn(0.25f, 4f),
                            offsetX = offsetX.coerceIn(-2f, 2f),
                            offsetY = offsetY.coerceIn(-2f, 2f),
                            mirrored = isMirrored,
                        ),
                    )
                }

                val safeBrightness = brightness.coerceIn(-1f, 1f)
                val safeContrast = contrast.coerceIn(-1f, 1f)
                val safeSaturation = saturation.coerceIn(-100f, 100f)
                if (kotlin.math.abs(safeBrightness) > 0.001f) {
                    effects.add(Brightness(safeBrightness))
                }
                if (kotlin.math.abs(safeContrast) > 0.001f) {
                    effects.add(Contrast(safeContrast))
                }
                if (kotlin.math.abs(safeSaturation) > 0.001f) {
                    effects.add(
                        HslAdjustment.Builder()
                            .adjustSaturation(safeSaturation)
                            .build(),
                    )
                }

                exoPlayer.setVideoEffects(effects)
                exoPlayer.setMediaItem(MediaItem.fromUri(uri))
                exoPlayer.setVideoSurface(surface)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE

                exoPlayer.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            isPlayingInternal = isPlaying
                            if (isPlaying) startPositionPolling() else stopPositionPolling()
                        }

                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            if (videoSize.width > 0) {
                                videoWidth = videoSize.width
                                videoHeight = videoSize.height
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                if (!isBusy) return
                                isBusy = false
                                if (videoWidth == 0) {
                                    videoWidth = exoPlayer.videoSize.width
                                    videoHeight = exoPlayer.videoSize.height
                                }
                                try {
                                    exoPlayer.play()
                                    log(tag, "Player ACTIVE (${videoWidth}x${videoHeight})")
                                    onPrepared?.invoke(exoPlayer)
                                } catch (e: Throwable) {
                                    log(tag, "Start failed: ${e.message}")
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            isBusy = false
                            log(tag, "Player Error: ${error.errorCodeName} | ${error.message}")
                            player?.release()
                            player = null
                            isPlayingInternal = false
                        }
                    },
                )

                exoPlayer.prepare()
            } catch (e: Throwable) {
                isBusy = false
                log(tag, "Prepare failed: ${e.message}")
                try {
                    player?.release()
                } catch (_: Throwable) {
                }
                player = null
                isPlayingInternal = false
            }
        }
    }

    /**
     * MatrixTransformation keeps the output frame size fixed while allowing
     * zoom/stretch and free X/Y movement. Media3 matrices use normalized device
     * coordinates, so the preview's normalized offsets map directly here.
     */
    private class UserTransformEffect(
        private val scaleX: Float,
        private val scaleY: Float,
        private val offsetX: Float,
        private val offsetY: Float,
        private val mirrored: Boolean,
    ) : MatrixTransformation {
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val matrix = Matrix()
            val x = scaleX * if (mirrored) -1f else 1f
            matrix.postScale(x, scaleY)
            matrix.postTranslate(offsetX, -offsetY)
            return matrix
        }
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            player?.let {
                currentPositionInternal = it.currentPosition
                if (isPlayingInternal) mainHandler.postDelayed(this, 500)
            }
        }
    }

    private fun startPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
        mainHandler.post(positionPoller)
    }

    private fun stopPositionPolling() {
        mainHandler.removeCallbacks(positionPoller)
    }
}
