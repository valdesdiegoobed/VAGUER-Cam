package com.hazbu.xcam.ui

import com.hazbu.xcam.data.Constants

data class TransformState(
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var rotation: Int = 0,
    var mirrored: Boolean = false,
    var fitMode: String = Constants.FIT_MODE_FIT,
    var brightness: Float = 0f,
    var contrast: Float = 0f,
    var saturation: Float = 0f,
    var sharpness: Float = 0f,
)
