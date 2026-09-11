package com.hazbu.xcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.ImageProcessor
import com.hazbu.xcam.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Conservative VAGUER Cam editor.
 *
 * This activity intentionally keeps the lifecycle close to the original xCam
 * manager and removes two risky behaviors from the first VAGUER editor:
 * 1) no Media3 photo conversion starts while the user is still editing;
 * 2) no discrete Material Slider is synchronized on every pinch frame.
 */
class SafeMainActivity : AppCompatActivity() {

    companion object {
        private const val SOURCE_PREFIX = "source."
        private const val OUTPUT_VIDEO_NAME = "virtual.mp4"
        private const val PREPARED_IMAGE_NAME = "prepared.jpg"
    }

    private lateinit var ivPreview: TransformableImageView
    private lateinit var tvNoPreview: TextView
    private lateinit var tvMediaType: TextView
    private lateinit var tvScaleX: TextView
    private lateinit var tvScaleY: TextView
    private lateinit var tvOffsetX: TextView
    private lateinit var tvOffsetY: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var tvRotation: TextView
    private lateinit var tvBrightness: TextView
    private lateinit var tvContrast: TextView
    private lateinit var tvSaturation: TextView
    private lateinit var tvSharpness: TextView
    private lateinit var tvModuleStatus: TextView

    private lateinit var btnSelectMedia: MaterialButton
    private lateinit var btnApply: MaterialButton
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnCenter: MaterialButton
    private lateinit var btnFit: MaterialButton
    private lateinit var btnFill: MaterialButton
    private lateinit var btnStretch: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnEnhance: MaterialButton
    private lateinit var btnAutoFrame: MaterialButton
    private lateinit var btnCopyCoordinates: MaterialButton

    private lateinit var sliderScaleX: Slider
    private lateinit var sliderScaleY: Slider
    private lateinit var sliderOffsetX: Slider
    private lateinit var sliderOffsetY: Slider
    private lateinit var sliderRotation: Slider
    private lateinit var sliderBrightness: Slider
    private lateinit var sliderContrast: Slider
    private lateinit var sliderSaturation: Slider
    private lateinit var sliderSharpness: Slider

    private lateinit var cardModuleStatus: MaterialCardView
    private lateinit var cardScopedApps: MaterialCardView

    private var state = TransformState()
    private var currentSourcePath = ""
    private var currentIsImage = false
    private var previewBitmap: Bitmap? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val settingsNotifyHandler = Handler(Looper.getMainLooper())
    private val settingsNotifyRunnable = Runnable {
        runCatching {
            contentResolver.notifyChange(
                Uri.parse("content://${Constants.AUTHORITY}"),
                null,
            )
        }
    }

    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { copyMediaToInternal(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }
        setContentView(R.layout.activity_main)
        runCatching { setupWindowInsets() }
        bindViews()
        setupControls()
        loadSettingsSafely()
        updateModuleStatusUI()
    }

    override fun onResume() {
        super.onResume()
        updateModuleStatusUI()
    }

    override fun onDestroy() {
        runCatching { ivPreview.setImageDrawable(null) }
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewBitmap = null
        settingsNotifyHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun setupWindowInsets() {
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val p = (16 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left + p, bars.top + p, bars.right + p, bars.bottom + p)
            insets
        }
    }

    private fun bindViews() {
        ivPreview = findViewById(R.id.iv_preview)
        tvNoPreview = findViewById(R.id.tv_no_preview)
        tvMediaType = findViewById(R.id.tv_media_type)
        tvScaleX = findViewById(R.id.tv_scale_x)
        tvScaleY = findViewById(R.id.tv_scale_y)
        tvOffsetX = findViewById(R.id.tv_offset_x)
        tvOffsetY = findViewById(R.id.tv_offset_y)
        tvCoordinates = findViewById(R.id.tv_coordinates)
        tvRotation = findViewById(R.id.tv_rotation)
        tvBrightness = findViewById(R.id.tv_brightness)
        tvContrast = findViewById(R.id.tv_contrast)
        tvSaturation = findViewById(R.id.tv_saturation)
        tvSharpness = findViewById(R.id.tv_sharpness)
        tvModuleStatus = findViewById(R.id.tv_module_status)

        btnSelectMedia = findViewById(R.id.btn_select_media)
        btnApply = findViewById(R.id.btn_apply)
        btnDeleteMedia = findViewById(R.id.btn_delete_media)
        btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left)
        btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnCenter = findViewById(R.id.btn_center)
        btnFit = findViewById(R.id.btn_fit)
        btnFill = findViewById(R.id.btn_fill)
        btnStretch = findViewById(R.id.btn_stretch)
        btnReset = findViewById(R.id.btn_reset)
        btnEnhance = findViewById(R.id.btn_enhance)
        btnAutoFrame = findViewById(R.id.btn_auto_frame)
        btnCopyCoordinates = findViewById(R.id.btn_copy_coordinates)

        sliderScaleX = findViewById(R.id.slider_scale_x)
        sliderScaleY = findViewById(R.id.slider_scale_y)
        sliderOffsetX = findViewById(R.id.slider_offset_x)
        sliderOffsetY = findViewById(R.id.slider_offset_y)
        sliderRotation = findViewById(R.id.slider_rotation)
        sliderBrightness = findViewById(R.id.slider_brightness)
        sliderContrast = findViewById(R.id.slider_contrast)
        sliderSaturation = findViewById(R.id.slider_saturation)
        sliderSharpness = findViewById(R.id.slider_sharpness)

        cardModuleStatus = findViewById(R.id.card_module_status)
        cardScopedApps = findViewById(R.id.card_scoped_apps)
    }

    private fun setupControls() {
        configureContinuousSlider(sliderScaleX, 0.25f, 4f, 1f)
        configureContinuousSlider(sliderScaleY, 0.25f, 4f, 1f)
        configureContinuousSlider(sliderOffsetX, -2f, 2f, 0f)
        configureContinuousSlider(sliderOffsetY, -2f, 2f, 0f)
        configureContinuousSlider(sliderRotation, 0f, 359f, 0f)
        sliderRotation.stepSize = 1f
        configureContinuousSlider(sliderBrightness, -1f, 1f, 0f)
        configureContinuousSlider(sliderContrast, -1f, 1f, 0f)
        configureContinuousSlider(sliderSaturation, -100f, 100f, 0f)
        configureContinuousSlider(sliderSharpness, 0f, 1f, 0f)

        ivPreview.onTransformChanged = { changed ->
            state.scaleX = changed.scaleX
            state.scaleY = changed.scaleY
            state.offsetX = changed.offsetX
            state.offsetY = changed.offsetY
            state.rotation = changed.rotation
            state.mirrored = changed.mirrored
            state.fitMode = changed.fitMode
            syncTransformSliders()
            updateMirrorButton()
            saveState()
        }

        btnSelectMedia.setOnClickListener {
            mediaPickerLauncher.launch(arrayOf("image/*", "video/*"))
        }
        btnApply.setOnClickListener { applyCurrentMedia() }
        btnDeleteMedia.setOnClickListener { deleteMedia() }
        btnRotateLeft.setOnClickListener { ivPreview.rotateBy(-90) }
        btnRotateRight.setOnClickListener { ivPreview.rotateBy(90) }
        btnMirror.setOnClickListener { ivPreview.setMirrored(!state.mirrored) }
        btnCenter.setOnClickListener { ivPreview.centerContent() }
        btnFit.setOnClickListener { ivPreview.setFitMode(Constants.FIT_MODE_FIT) }
        btnFill.setOnClickListener { ivPreview.setFitMode(Constants.FIT_MODE_FILL) }
        btnStretch.setOnClickListener { ivPreview.setFitMode(Constants.FIT_MODE_STRETCH) }

        btnReset.setOnClickListener {
            val brightness = state.brightness
            val contrast = state.contrast
            val saturation = state.saturation
            val sharpness = state.sharpness
            state = TransformState(
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
                sharpness = sharpness,
            )
            ivPreview.setTransformState(state)
            syncAllControls()
            saveState()
        }

        btnEnhance.setOnClickListener {
            state.brightness = 0.05f
            state.contrast = 0.12f
            state.saturation = 8f
            state.sharpness = if (currentIsImage) 0.25f else 0f
            syncImageSliders()
            applyPreviewColorFilter()
            saveState()
        }

        btnAutoFrame.setOnClickListener { runAutoFrame() }
        btnCopyCoordinates.setOnClickListener { copyCoordinates() }

        sliderScaleX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.scaleX = value.coerceIn(0.25f, 4f)
                ivPreview.setScaleFactors(state.scaleX, state.scaleY)
            }
        }
        sliderScaleY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.scaleY = value.coerceIn(0.25f, 4f)
                ivPreview.setScaleFactors(state.scaleX, state.scaleY)
            }
        }
        sliderOffsetX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.offsetX = value.coerceIn(-2f, 2f)
                ivPreview.setOffsets(state.offsetX, state.offsetY)
            }
        }
        sliderOffsetY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.offsetY = value.coerceIn(-2f, 2f)
                ivPreview.setOffsets(state.offsetX, state.offsetY)
            }
        }
        sliderRotation.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.rotation = value.toInt().coerceIn(0, 359)
                ivPreview.setRotationDegrees(state.rotation)
            }
        }
        sliderBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.brightness = value.coerceIn(-1f, 1f)
                onImageAdjustmentChanged()
            }
        }
        sliderContrast.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.contrast = value.coerceIn(-1f, 1f)
                onImageAdjustmentChanged()
            }
        }
        sliderSaturation.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.saturation = value.coerceIn(-100f, 100f)
                onImageAdjustmentChanged()
            }
        }
        sliderSharpness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.sharpness = value.coerceIn(0f, 1f)
                updateLabels()
                saveState()
            }
        }
    }

    private fun configureContinuousSlider(slider: Slider, from: Float, to: Float, value: Float) {
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = 0f
        slider.value = value.coerceIn(from, to)
    }

    private fun copyMediaToInternal(uri: Uri) {
        try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = mime.startsWith("image/")
            val isVideo = mime.startsWith("video/")
            if (!isImage && !isVideo) {
                toast(getString(R.string.toast_invalid_media))
                return
            }

            clearPreviewBitmap()
            deleteInternalMediaFiles()

            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime)
                ?.lowercase()
                ?: if (isImage) "jpg" else "mp4"
            val source = File(filesDir, SOURCE_PREFIX + extension)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output -> input.copyTo(output) }
            } ?: error("No se pudo abrir el archivo")

            currentSourcePath = source.absolutePath
            currentIsImage = isImage
            state = TransformState()

            getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
                putString(Constants.KEY_SOURCE_PATH, currentSourcePath)
                putBoolean(Constants.KEY_SOURCE_IS_IMAGE, currentIsImage)
                putString(Constants.KEY_MEDIA_PATH, "")
            }
            saveState()
            loadPreview()
            syncAllControls()

            if (currentIsImage) {
                toast("Foto lista para editar. Pulsa Aplicar a cámara al terminar.")
            } else {
                toast("Video listo para editar. Pulsa Aplicar a cámara al terminar.")
            }
        } catch (t: Throwable) {
            toast(getString(R.string.toast_media_import_failed, t.message ?: "error"))
        }
    }

    private fun loadSettingsSafely() {
        try {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            currentSourcePath = prefs.getString(Constants.KEY_SOURCE_PATH, "") ?: ""
            currentIsImage = prefs.getBoolean(Constants.KEY_SOURCE_IS_IMAGE, false)
            state = TransformState(
                scaleX = safeFloat(prefs.getFloat(Constants.KEY_SCALE_X, 1f), 1f, 0.25f, 4f),
                scaleY = safeFloat(prefs.getFloat(Constants.KEY_SCALE_Y, 1f), 1f, 0.25f, 4f),
                offsetX = safeFloat(prefs.getFloat(Constants.KEY_OFFSET_X, 0f), 0f, -2f, 2f),
                offsetY = safeFloat(prefs.getFloat(Constants.KEY_OFFSET_Y, 0f), 0f, -2f, 2f),
                rotation = ((prefs.getInt(Constants.KEY_ROTATION_ANGLE, 0) % 360) + 360) % 360,
                mirrored = prefs.getBoolean(Constants.KEY_IS_MIRRORED, false),
                fitMode = prefs.getString(Constants.KEY_FIT_MODE, Constants.FIT_MODE_FIT)
                    ?: Constants.FIT_MODE_FIT,
                brightness = safeFloat(prefs.getFloat(Constants.KEY_BRIGHTNESS, 0f), 0f, -1f, 1f),
                contrast = safeFloat(prefs.getFloat(Constants.KEY_CONTRAST, 0f), 0f, -1f, 1f),
                saturation = safeFloat(prefs.getFloat(Constants.KEY_SATURATION, 0f), 0f, -100f, 100f),
                sharpness = safeFloat(prefs.getFloat(Constants.KEY_SHARPNESS, 0f), 0f, 0f, 1f),
            )
            loadPreview()
            syncAllControls()
        } catch (_: Throwable) {
            getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { clear() }
            state = TransformState()
            currentSourcePath = ""
            currentIsImage = false
            showEmptyPreview()
            syncAllControls()
        }
    }

    private fun loadPreview() {
        if (currentSourcePath.isBlank() || !File(currentSourcePath).exists()) {
            showEmptyPreview()
            return
        }

        try {
            val bitmap = if (currentIsImage) {
                ImageProcessor.decodeOriented(currentSourcePath, 1024)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(currentSourcePath)
                    retriever.getFrameAtTime(500_000)
                } finally {
                    retriever.release()
                }
            }

            if (bitmap == null) {
                showEmptyPreview()
                return
            }

            setPreviewBitmap(bitmap)
            tvNoPreview.visibility = View.GONE
            btnDeleteMedia.visibility = View.VISIBLE
            btnApply.isEnabled = true
            btnAutoFrame.isEnabled = currentIsImage
            btnCopyCoordinates.isEnabled = true
            sliderSharpness.isEnabled = currentIsImage
            tvMediaType.text = if (currentIsImage) {
                getString(R.string.label_photo_ready)
            } else {
                getString(R.string.label_video_ready)
            }
            ivPreview.setTransformState(state)
            applyPreviewColorFilter()
        } catch (_: Throwable) {
            showEmptyPreview()
        }
    }

    private fun runAutoFrame() {
        if (!currentIsImage) {
            toast(getString(R.string.toast_auto_frame_photo_only))
            return
        }
        val bitmap = previewBitmap
        if (bitmap == null || bitmap.isRecycled) {
            toast(getString(R.string.toast_select_media_first))
            return
        }

        if (ivPreview.width <= 0 || ivPreview.height <= 0) {
            ivPreview.post { runAutoFrame() }
            return
        }

        btnAutoFrame.isEnabled = false
        tvMediaType.text = getString(R.string.label_analyzing_face)

        AutoFrameEngine.detect(
            bitmap = bitmap,
            viewWidth = ivPreview.width,
            viewHeight = ivPreview.height,
            onSuccess = { result ->
                runOnUiThread {
                    applyReferencePattern(result)
                    btnAutoFrame.isEnabled = currentIsImage && previewBitmap != null
                    tvMediaType.text = getString(R.string.label_photo_ready)
                    if (result == null) {
                        toast(getString(R.string.toast_auto_frame_no_face))
                    } else {
                        toast(getString(R.string.toast_auto_frame_applied))
                    }
                }
            },
            onFailure = { error ->
                runOnUiThread {
                    btnAutoFrame.isEnabled = currentIsImage && previewBitmap != null
                    tvMediaType.text = getString(R.string.label_photo_ready)
                    toast(getString(R.string.toast_auto_frame_failed, error.message ?: "error"))
                }
            },
        )
    }

    private fun applyReferencePattern(result: AutoFrameEngine.FrameResult?) {
        val zoom = result?.scale ?: AutoFrameEngine.REFERENCE_FALLBACK_SCALE
        state = state.copy(
            scaleX = zoom,
            scaleY = zoom,
            offsetX = result?.offsetX ?: 0f,
            offsetY = result?.offsetY ?: 0f,
            rotation = 0,
            mirrored = false,
            fitMode = Constants.FIT_MODE_FIT,
            brightness = AutoFrameEngine.REFERENCE_BRIGHTNESS,
            contrast = AutoFrameEngine.REFERENCE_CONTRAST,
            saturation = AutoFrameEngine.REFERENCE_SATURATION,
            sharpness = AutoFrameEngine.REFERENCE_SHARPNESS,
        )
        syncAllControls()
        saveState()
    }

    private fun copyCoordinates() {
        val text = String.format(
            java.util.Locale.US,
            "VAGUER: X=%.2f; Y=%.2f; Ancho=%.2fx; Alto=%.2fx; Rot=%d°; Brillo=%d%%; Contraste=%d%%; Saturación=%d%%; Nitidez=%d%%",
            state.offsetX,
            state.offsetY,
            state.scaleX,
            state.scaleY,
            state.rotation,
            (state.brightness * 100).toInt(),
            (state.contrast * 100).toInt(),
            state.saturation.toInt(),
            (state.sharpness * 100).toInt(),
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Coordenadas VAGUER", text))
        toast(getString(R.string.toast_coordinates_copied))
    }

    private fun applyCurrentMedia() {
        if (currentSourcePath.isBlank() || !File(currentSourcePath).exists()) {
            toast(getString(R.string.toast_select_media_first))
            return
        }
        saveState()
        if (currentIsImage) {
            preparePhotoForCamera()
        } else {
            saveMediaPath(currentSourcePath)
            toast(getString(R.string.toast_settings_saved))
        }
    }

    private fun preparePhotoForCamera() {
        val sourcePath = currentSourcePath
        val sharpness = state.sharpness
        btnApply.isEnabled = false
        btnSelectMedia.isEnabled = false
        tvMediaType.text = getString(R.string.label_processing_photo)

        worker.execute {
            try {
                val oriented = ImageProcessor.decodeOriented(sourcePath, 1280)
                    ?: error("No se pudo leer la fotografía")
                val processed = ImageProcessor.sharpen(oriented, sharpness)
                val prepared = File(filesDir, PREPARED_IMAGE_NAME)
                FileOutputStream(prepared).use { stream ->
                    processed.compress(Bitmap.CompressFormat.JPEG, 93, stream)
                }
                if (processed !== oriented && !processed.isRecycled) processed.recycle()
                if (!oriented.isRecycled) oriented.recycle()

                runOnUiThread {
                    val output = File(filesDir, OUTPUT_VIDEO_NAME)
                    try {
                        MediaConverter.convertImageToMp4(
                            this,
                            Uri.fromFile(prepared),
                            output,
                        ) { success ->
                            runOnUiThread {
                                btnApply.isEnabled = true
                                btnSelectMedia.isEnabled = true
                                tvMediaType.text = getString(R.string.label_photo_ready)
                                if (success && output.exists()) {
                                    saveMediaPath(output.absolutePath)
                                    toast(getString(R.string.toast_settings_saved))
                                } else {
                                    toast(getString(R.string.toast_conversion_failed))
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        btnApply.isEnabled = true
                        btnSelectMedia.isEnabled = true
                        tvMediaType.text = getString(R.string.label_photo_ready)
                        toast(getString(R.string.toast_conversion_failed))
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    btnApply.isEnabled = true
                    btnSelectMedia.isEnabled = true
                    tvMediaType.text = getString(R.string.label_photo_ready)
                    toast(getString(R.string.toast_media_import_failed, t.message ?: "error"))
                }
            }
        }
    }

    private fun saveMediaPath(path: String) {
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
            putString(Constants.KEY_MEDIA_PATH, path)
        }
        notifyOutputChanged(immediate = true)
    }

    private fun saveState() {
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(Constants.KEY_IS_MIRRORED, state.mirrored)
            putInt(Constants.KEY_ROTATION_ANGLE, state.rotation)
            putFloat(Constants.KEY_SCALE_X, state.scaleX)
            putFloat(Constants.KEY_SCALE_Y, state.scaleY)
            putFloat(Constants.KEY_OFFSET_X, state.offsetX)
            putFloat(Constants.KEY_OFFSET_Y, state.offsetY)
            putString(Constants.KEY_FIT_MODE, state.fitMode)
            putFloat(Constants.KEY_BRIGHTNESS, state.brightness)
            putFloat(Constants.KEY_CONTRAST, state.contrast)
            putFloat(Constants.KEY_SATURATION, state.saturation)
            putFloat(Constants.KEY_SHARPNESS, state.sharpness)
        }
        notifyOutputChanged()
    }

    private fun notifyOutputChanged(immediate: Boolean = false) {
        settingsNotifyHandler.removeCallbacks(settingsNotifyRunnable)
        if (immediate) {
            settingsNotifyHandler.post(settingsNotifyRunnable)
        } else {
            settingsNotifyHandler.postDelayed(settingsNotifyRunnable, 180L)
        }
    }

    private fun onImageAdjustmentChanged() {
        applyPreviewColorFilter()
        updateLabels()
        saveState()
    }

    private fun applyPreviewColorFilter() {
        if (
            abs(state.brightness) < 0.001f &&
            abs(state.contrast) < 0.001f &&
            abs(state.saturation) < 0.001f
        ) {
            ivPreview.clearColorFilter()
            return
        }

        val saturationFactor = (1f + state.saturation / 100f).coerceAtLeast(0f)
        val color = ColorMatrix().apply { setSaturation(saturationFactor) }
        val contrastFactor = if (state.contrast >= 0f) {
            1f + state.contrast * 1.5f
        } else {
            1f + state.contrast
        }
        val translate = 128f * (1f - contrastFactor) + state.brightness * 255f
        val adjustment = ColorMatrix(
            floatArrayOf(
                contrastFactor, 0f, 0f, 0f, translate,
                0f, contrastFactor, 0f, 0f, translate,
                0f, 0f, contrastFactor, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        color.postConcat(adjustment)
        ivPreview.colorFilter = ColorMatrixColorFilter(color)
    }

    private fun syncAllControls() {
        ivPreview.setTransformState(state)
        syncTransformSliders()
        syncImageSliders()
        updateMirrorButton()
        updateLabels()
        applyPreviewColorFilter()
    }

    private fun syncTransformSliders() {
        sliderScaleX.value = state.scaleX.coerceIn(0.25f, 4f)
        sliderScaleY.value = state.scaleY.coerceIn(0.25f, 4f)
        sliderOffsetX.value = state.offsetX.coerceIn(-2f, 2f)
        sliderOffsetY.value = state.offsetY.coerceIn(-2f, 2f)
        sliderRotation.value = state.rotation.coerceIn(0, 359).toFloat()
        updateLabels()
    }

    private fun syncImageSliders() {
        sliderBrightness.value = state.brightness.coerceIn(-1f, 1f)
        sliderContrast.value = state.contrast.coerceIn(-1f, 1f)
        sliderSaturation.value = state.saturation.coerceIn(-100f, 100f)
        sliderSharpness.value = state.sharpness.coerceIn(0f, 1f)
        updateLabels()
    }

    private fun updateLabels() {
        tvScaleX.text = getString(R.string.label_width_value, state.scaleX)
        tvScaleY.text = getString(R.string.label_height_value, state.scaleY)
        tvOffsetX.text = getString(R.string.label_offset_x_value, state.offsetX)
        tvOffsetY.text = getString(R.string.label_offset_y_value, state.offsetY)
        tvCoordinates.text = getString(
            R.string.label_coordinates_value,
            state.offsetX,
            state.offsetY,
            (state.scaleX + state.scaleY) / 2f,
            state.rotation,
        )
        tvRotation.text = getString(R.string.label_rotation_value, state.rotation)
        tvBrightness.text = getString(R.string.label_brightness_value, (state.brightness * 100).toInt())
        tvContrast.text = getString(R.string.label_contrast_value, (state.contrast * 100).toInt())
        tvSaturation.text = getString(R.string.label_saturation_value, state.saturation.toInt())
        tvSharpness.text = getString(R.string.label_sharpness_value, (state.sharpness * 100).toInt())
    }

    private fun updateMirrorButton() {
        btnMirror.isSelected = state.mirrored
        btnMirror.alpha = if (state.mirrored) 1f else 0.78f
    }

    private fun deleteMedia() {
        clearPreviewBitmap()
        deleteInternalMediaFiles()
        currentSourcePath = ""
        currentIsImage = false
        state = TransformState()
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit { clear() }
        notifyOutputChanged(immediate = true)
        showEmptyPreview()
        syncAllControls()
        toast(getString(R.string.toast_media_removed))
    }

    private fun deleteInternalMediaFiles() {
        filesDir.listFiles()?.filter {
            it.name.startsWith(SOURCE_PREFIX) ||
                it.name == OUTPUT_VIDEO_NAME ||
                it.name == PREPARED_IMAGE_NAME ||
                it.name.startsWith("virtual.")
        }?.forEach { runCatching { it.delete() } }
    }

    private fun showEmptyPreview() {
        clearPreviewBitmap()
        ivPreview.clearColorFilter()
        tvNoPreview.visibility = View.VISIBLE
        btnDeleteMedia.visibility = View.GONE
        btnApply.isEnabled = false
        btnAutoFrame.isEnabled = false
        btnCopyCoordinates.isEnabled = false
        sliderSharpness.isEnabled = false
        tvMediaType.text = getString(R.string.label_no_media)
    }

    private fun setPreviewBitmap(bitmap: Bitmap) {
        ivPreview.setImageDrawable(null)
        previewBitmap?.let { old -> if (old !== bitmap && !old.isRecycled) old.recycle() }
        previewBitmap = bitmap
        ivPreview.setImageBitmap(bitmap)
    }

    private fun clearPreviewBitmap() {
        runCatching { ivPreview.setImageDrawable(null) }
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        previewBitmap = null
    }

    private fun updateModuleStatusUI() {
        // Vector owns activation/scope. Keeping the editor independent from a
        // manager service prevents manager compatibility from crashing the UI.
        tvModuleStatus.text = "Editor listo · módulo se activa desde Vector"
        cardModuleStatus.alpha = 1f
        cardScopedApps.visibility = View.GONE
    }

    private fun safeFloat(value: Float, fallback: Float, min: Float, max: Float): Float {
        return if (value.isFinite()) value.coerceIn(min, max) else fallback
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
