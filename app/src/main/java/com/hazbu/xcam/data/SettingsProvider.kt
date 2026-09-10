package com.hazbu.xcam.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val prefs = context?.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs?.getString(Constants.KEY_MEDIA_PATH, "") ?: ""
        val fileName = if (path.isNotEmpty()) File(path).name else "media"
        val videoUri = "content://${Constants.AUTHORITY}/$fileName"

        val columns = arrayOf(
            Constants.KEY_MEDIA_PATH,
            Constants.KEY_IS_ENABLED,
            Constants.KEY_IS_MIRRORED,
            Constants.KEY_ROTATION_ANGLE,
            Constants.KEY_SCALE_X,
            Constants.KEY_SCALE_Y,
            Constants.KEY_OFFSET_X,
            Constants.KEY_OFFSET_Y,
            Constants.KEY_FIT_MODE,
            Constants.KEY_BRIGHTNESS,
            Constants.KEY_CONTRAST,
            Constants.KEY_SATURATION,
        )
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            arrayOf(
                videoUri,
                "1",
                if (prefs?.getBoolean(Constants.KEY_IS_MIRRORED, false) == true) "1" else "0",
                (prefs?.getInt(Constants.KEY_ROTATION_ANGLE, 0) ?: 0).toString(),
                (prefs?.getFloat(Constants.KEY_SCALE_X, 1f) ?: 1f).toString(),
                (prefs?.getFloat(Constants.KEY_SCALE_Y, 1f) ?: 1f).toString(),
                (prefs?.getFloat(Constants.KEY_OFFSET_X, 0f) ?: 0f).toString(),
                (prefs?.getFloat(Constants.KEY_OFFSET_Y, 0f) ?: 0f).toString(),
                prefs?.getString(Constants.KEY_FIT_MODE, Constants.FIT_MODE_FIT)
                    ?: Constants.FIT_MODE_FIT,
                (prefs?.getFloat(Constants.KEY_BRIGHTNESS, 0f) ?: 0f).toString(),
                (prefs?.getFloat(Constants.KEY_CONTRAST, 0f) ?: 0f).toString(),
                (prefs?.getFloat(Constants.KEY_SATURATION, 0f) ?: 0f).toString(),
            ),
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: return null
        val file = File(context.filesDir, fileName)
        if (file.exists()) {
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: Exception) {
                null
            }
        }

        val fallbackFile = context.filesDir.listFiles { _, name ->
            name.startsWith("virtual.") || name.startsWith("source.")
        }?.firstOrNull()
        return try {
            fallbackFile?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String {
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return when {
            mimeType?.startsWith("image/") == true -> mimeType
            mimeType?.startsWith("video/") == true -> mimeType
            else -> "video/mp4"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
