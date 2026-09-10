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
import androidx.media3.exoplayer.ExoPlayer
import com.hazbu.xcam.data.Constants
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Handles Media3/ExoPlayer lifecycle and the visual output sent to the virtual
 * camera surface.
 *
 * VAGUER Cam first normalizes the media onto the same square canvas used by the
 * editor and then applies one fixed-size matrix for zoom, movement, mirror and
 * rotation. Keeping those operations in one matrix is important: Media3's
 * ScaleAndRotateTransformation intentionally expands the output dimensions to
 * preserve all pixels, which makes a scale look like a resize instead of a real
 * camera zoom. This matrix keeps the output frame fixed, so pixels outside the
 * canvas are clipped exactly like the editor preview.
 */
@UnstableApi
class MediaEngine(private val logAction: (String) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile
    private var isBusy = false

    @Volatile
    private var isPlayingInternal = false

    @Volatile
    private var currentPositionInternal = 0L

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    val isPlaying: Boolean
        get() = isPlayingInternal

    val currentPosition: Long
        get() = if (Looper.myLooper() == Looper.getMainLooper()) {
            player?.currentPosition ?: 0L
        } else {
            currentPositionInternal
        }

    private fun log(tag: String, msg: String) {
        logAction("[$tag] $msg")
    }

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
                log(
                    tag,
                    "Loading media: $path | rot=$rotationAngle | scale=${"%.2f".format(scaleX)}x${"%.2f".format(scaleY)} | offset=${"%.2f".format(offsetX)},${"%.2f".format(offsetY)} | fit=$fitMode",
                )

                val renderersFactory =
                    androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
                        .setExtensionRendererMode(
                            androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
                        )

                val exoPlayer =
                    ExoPlayer.Builder(context.applicationContext, renderersFactory).build()
                player = exoPlayer

                val effects = mutableListOf<Effect>()

                // The editor preview is square. Normalize the source to that same
                // geometry before applying any user transform. A 90° rotation
                // therefore never changes the output dimensions or causes the
                // target app to reinterpret portrait/landscape metadata.
                val presentationLayout =
                    when (fitMode) {
                        Constants.FIT_MODE_FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                        Constants.FIT_MODE_STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                        else -> Presentation.LAYOUT_SCALE_TO_FIT
                    }
                effects.add(Presentation.createForAspectRatio(1f, presentationLayout))

                val normalizedRotation = ((rotationAngle % 360) + 360) % 360
                if (
                    isMirrored ||
                    normalizedRotation != 0 ||
                    abs(scaleX - 1f) > 0.001f ||
                    abs(scaleY - 1f) > 0.001f ||
                    abs(offsetX) > 0.001f ||
                    abs(offsetY) > 0.001f
                ) {
                    effects.add(
                        UserTransformEffect(
                            scaleX = scaleX.coerceIn(0.25f, 4f),
                            scaleY = scaleY.coerceIn(0.25f, 4f),
                            offsetX = offsetX.coerceIn(-2f, 2f),
                            offsetY = offsetY.coerceIn(-2f, 2f),
                            rotationAngle = normalizedRotation,
                            mirrored = isMirrored,
                        ),
                    )
                }

                val safeBrightness = brightness.coerceIn(-1f, 1f)
                val safeContrast = contrast.coerceIn(-1f, 1f)
                val safeSaturation = saturation.coerceIn(-100f, 100f)

                if (abs(safeBrightness) > 0.001f) {
                    effects.add(Brightness(safeBrightness))
                }
                if (abs(safeContrast) > 0.001f) {
                    effects.add(Contrast(safeContrast))
                }
                if (abs(safeSaturation) > 0.001f) {
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
                            log(
                                tag,
                                "Player Error: ${error.errorCodeName} | ${error.message}",
                            )
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
     * MatrixTransformation's default configure() keeps the input dimensions
     * unchanged. That is exactly what a camera-style zoom needs: scale > 1
     * enlarges the image while the fixed output canvas clips the edges.
     *
     * Android View rotation is visually clockwise because screen Y grows
     * downward. Media3 matrices operate in NDC where Y grows upward, so the
     * editor angle is negated here to make both previews agree.
     */
    private class UserTransformEffect(
        private val scaleX: Float,
        private val scaleY: Float,
        private val offsetX: Float,
        private val offsetY: Float,
        private val rotationAngle: Int,
        private val mirrored: Boolean,
    ) : MatrixTransformation {

        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val sx = scaleX * if (mirrored) -1f else 1f
            val sy = scaleY

            val radians = Math.toRadians(-rotationAngle.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()

            // NDC translation: +X is right; +Y is up. The editor's +Y is down.
            val tx = offsetX
            val ty = -offsetY

            return Matrix().apply {
                setValues(
                    floatArrayOf(
                        c * sx, -s * sy, tx,
                        s * sx, c * sy, ty,
                        0f, 0f, 1f,
                    ),
                )
            }
        }
    }

    private val positionPoller =
        object : Runnable {
            override fun run() {
                player?.let {
                    currentPositionInternal = it.currentPosition
                    if (isPlayingInternal) {
                        mainHandler.postDelayed(this, 500)
                    }
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
