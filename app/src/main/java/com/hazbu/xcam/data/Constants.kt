package com.hazbu.xcam.data

object Constants {
    const val PREFS_NAME = "vaguer_cam_prefs"

    const val KEY_MEDIA_PATH = "media_path"
    const val KEY_SOURCE_PATH = "source_path"
    const val KEY_SOURCE_IS_IMAGE = "source_is_image"
    const val KEY_IS_ENABLED = "is_enabled"

    const val KEY_IS_MIRRORED = "is_mirrored"
    const val KEY_ROTATION_ANGLE = "rotation_angle"
    const val KEY_SCALE_X = "scale_x"
    const val KEY_SCALE_Y = "scale_y"
    const val KEY_OFFSET_X = "offset_x"
    const val KEY_OFFSET_Y = "offset_y"
    const val KEY_FIT_MODE = "fit_mode"

    const val KEY_BRIGHTNESS = "brightness"
    const val KEY_CONTRAST = "contrast"
    const val KEY_SATURATION = "saturation"
    const val KEY_SHARPNESS = "sharpness"

    const val AUTHORITY = "com.vaguer.cam.provider"

    const val FIT_MODE_FIT = "FIT"
    const val FIT_MODE_FILL = "FILL"
    const val FIT_MODE_STRETCH = "STRETCH"

    // Keep the square working canvas used by the original xCam pipeline.
    const val DEFAULT_CAPTURE_WIDTH = 1280
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val DUMMY_SURFACE_TEXTURE_ID = 999
    const val MIN_SESSION_DEBOUNCE_MS = 100L
    const val STREAM_FRAME_INTERVAL_MS = 500L
}
