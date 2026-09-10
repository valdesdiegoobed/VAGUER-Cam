package com.hazbu.xcam.core.orientation

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.WindowManager

/**
 * Tracks the real camera sensor/display orientation used by the target app.
 *
 * Camera apps and WebRTC treat incoming frames as raw sensor-oriented data and
 * rotate them for the current display. VAGUER Cam's editor, however, shows a
 * display-oriented image. We therefore pre-rotate the already edited frame by
 * the camera/display relative rotation before sending it to the target. The
 * target app's normal camera transform then cancels that pre-rotation and the
 * user sees exactly the editor orientation.
 */
class CameraOrientationManager(
    private val logAction: (String) -> Unit,
) {
    @Volatile
    private var sensorOrientation: Int? = null

    @Volatile
    private var facingFront: Boolean = false

    @Volatile
    private var activeCameraId: String? = null

    fun updateCamera2(context: Context, cameraId: String) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val front =
                characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT

            sensorOrientation = normalizeDegrees(sensor)
            facingFront = front
            activeCameraId = cameraId

            val display = displayRotationDegrees(context)
            val compensation = computePreviewCompensation(sensor, front, display)
            logAction(
                "[ORIENTATION] camera=$cameraId sensor=$sensor front=$front " +
                    "display=$display compensation=$compensation",
            )
        } catch (t: Throwable) {
            logAction("[ORIENTATION] Camera orientation unavailable: ${t.message}")
        }
    }

    fun previewCompensationDegrees(context: Context): Int {
        val sensor = sensorOrientation ?: return 0
        val display = displayRotationDegrees(context)
        return computePreviewCompensation(sensor, facingFront, display)
    }

    fun clear() {
        sensorOrientation = null
        facingFront = false
        activeCameraId = null
    }

    private fun displayRotationDegrees(context: Context): Int {
        return try {
            val windowManager =
                context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return 0
            @Suppress("DEPRECATION")
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }
    }

    companion object {
        fun computePreviewCompensation(
            sensorOrientation: Int,
            facingFront: Boolean,
            displayRotationDegrees: Int,
        ): Int {
            val sensor = normalizeDegrees(sensorOrientation)
            val display = normalizeDegrees(displayRotationDegrees)
            return if (facingFront) {
                normalizeDegrees(sensor + display)
            } else {
                normalizeDegrees(sensor - display)
            }
        }

        private fun normalizeDegrees(value: Int): Int =
            ((value % 360) + 360) % 360
    }
}
