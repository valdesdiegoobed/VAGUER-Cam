package com.hazbu.xcam.core.settings

import android.content.Context
import android.database.Cursor
import androidx.core.net.toUri
import com.hazbu.xcam.data.Constants

class SettingsManager {
    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0
    var scaleX = 1f
    var scaleY = 1f
    var offsetX = 0f
    var offsetY = 0f
    var fitMode = Constants.FIT_MODE_FIT
    var brightness = 0f
    var contrast = 0f
    var saturation = 0f

    fun refreshSettings(context: Context) {
        try {
            val uri = "content://${Constants.AUTHORITY}".toUri()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                mediaPath = cursor.string(Constants.KEY_MEDIA_PATH)
                isMirrored = cursor.string(Constants.KEY_IS_MIRRORED) == "1"
                rotationAngle = cursor.string(Constants.KEY_ROTATION_ANGLE)?.toIntOrNull() ?: 0
                scaleX = cursor.string(Constants.KEY_SCALE_X)?.toFloatOrNull() ?: 1f
                scaleY = cursor.string(Constants.KEY_SCALE_Y)?.toFloatOrNull() ?: 1f
                offsetX = cursor.string(Constants.KEY_OFFSET_X)?.toFloatOrNull() ?: 0f
                offsetY = cursor.string(Constants.KEY_OFFSET_Y)?.toFloatOrNull() ?: 0f
                fitMode = cursor.string(Constants.KEY_FIT_MODE) ?: Constants.FIT_MODE_FIT
                brightness = cursor.string(Constants.KEY_BRIGHTNESS)?.toFloatOrNull() ?: 0f
                contrast = cursor.string(Constants.KEY_CONTRAST)?.toFloatOrNull() ?: 0f
                saturation = cursor.string(Constants.KEY_SATURATION)?.toFloatOrNull() ?: 0f
            }
        } catch (_: Exception) {
            // The target app may be starting before the manager provider is ready.
        }
    }

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}
