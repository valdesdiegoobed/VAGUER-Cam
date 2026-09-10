package com.hazbu.xcam.ui

import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.ImageProcessor
import com.hazbu.xcam.utils.MediaConverter
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {

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

    private lateinit var sliderScaleX: Slider
    private lateinit var sliderScaleY: Slider
    private lateinit var sliderBrightness: Slider
    private lateinit var sliderContrast: Slider
    private lateinit var sliderSaturation: Slider
    private lateinit var sliderSharpness: Slider

    private lateinit var cardModuleStatus: MaterialCardView
    private lateinit var cardScopedApps: MaterialCardView
    private lateinit var layoutScopedApps: LinearLayout

    private var state = TransformState()
    private var currentSourcePath = ""
    private var currentIsImage = false
    private var mXposedService: XposedService? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { copyMediaToInternal(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        XposedServiceHelper.registerListener(this)
        setupWindowInsets()
        bindViews()
        setupControls()
        loadSettings()
        updateModuleStatusUI()
    }

    override fun onResume() {
        super.onResume()
        updateModuleStatusUI()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onServiceBind(service: XposedService) {
        mXposedService = service
        runOnUiThread { updateModuleStatusUI() }
    }

    override fun onServiceDied(service: XposedService) {
        mXposedService = null
        runOnUiThread { updateModuleStatusUI() }
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

        sliderScaleX = findViewById(R.id.slider_scale_x)
        sliderScaleY = findViewById(R.id.slider_scale_y)
        sliderBrightness = findViewById(R.id.slider_brightness)
        sliderContrast = findViewById(R.id.slider_contrast)
        sliderSaturation = findViewById(R.id.slider_saturation)
        sliderSharpness = findViewById(R.id.slider_sharpness)

        cardModuleStatus = findViewById(R.id.card_module_status)
        cardScopedApps = findViewById(R.id.card_scoped_apps)
        layoutScopedApps = findViewById(R.id.layout_scoped_apps)
    }

    private fun setupControls() {
        configureSlider(sliderScaleX, 0.25f, 4f, 0.05f)
        configureSlider(sliderScaleY, 0.25f, 4f, 0.05f)
        configureSlider(sliderBrightness, -1f, 1f, 0.05f)
        configureSlider(sliderContrast, -1f, 1f, 0.05f)
        configureSlider(sliderSaturation, -100f, 100f, 5f)
        configureSlider(sliderSharpness, 0f, 1f, 0.05f)

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

        btnSelectMedia.setOnClickListener { openMediaPicker() }
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
            state = TransformState()
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

        sliderScaleX.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.scaleX = value
                ivPreview.setScaleFactors(state.scaleX, state.scaleY)
            }
        }
        sliderScaleY.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.scaleY = value
                ivPreview.setScaleFactors(state.scaleX, state.scaleY)
            }
        }

        sliderBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.brightness = value
                onImageAdjustmentChanged()
            }
        }
        sliderContrast.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.contrast = value
                onImageAdjustmentChanged()
            }
        }
        sliderSaturation.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.saturation = value
                onImageAdjustmentChanged()
            }
        }
        sliderSharpness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                state.sharpness = value
                updateLabels()
                saveState()
            }
        }
    }

    private fun configureSlider(slider: Slider, from: Float, to: Float, step: Float) {
        slider.valueFrom = from
        slider.valueTo = to
        slider.stepSize = step
    }

    private fun openMediaPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        mediaPickerLauncher.launch(intent)
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

            deleteInternalMediaFiles()
            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime)
                ?.lowercase()
                ?: if (isImage) "jpg" else "mp4"
            val source = File(filesDir, SOURCE_PREFIX + extension)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("No se pudo abrir el archivo")

            currentSourcePath = source.absolutePath
            currentIsImage = isImage
            state = TransformState()
            getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
                putString(Constants.KEY_SOURCE_PATH, currentSourcePath)
                putBoolean(Constants.KEY_SOURCE_IS_IMAGE, currentIsImage)
            }
            saveState()
            loadPreview()
            syncAllControls()

            if (currentIsImage) {
                preparePhotoForCamera(showSuccessToast = false)
            } else {
                saveMediaPath(currentSourcePath)
                toast(getString(R.string.toast_media_imported))
            }
        } catch (e: Exception) {
            toast(getString(R.string.toast_media_import_failed, e.message ?: "error"))
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        currentSourcePath = prefs.getString(Constants.KEY_SOURCE_PATH, "") ?: ""
        currentIsImage = prefs.getBoolean(Constants.KEY_SOURCE_IS_IMAGE, false)
        state = TransformState(
            scaleX = prefs.getFloat(Constants.KEY_SCALE_X, 1f),
            scaleY = prefs.getFloat(Constants.KEY_SCALE_Y, 1f),
            offsetX = prefs.getFloat(Constants.KEY_OFFSET_X, 0f),
            offsetY = prefs.getFloat(Constants.KEY_OFFSET_Y, 0f),
            rotation = prefs.getInt(Constants.KEY_ROTATION_ANGLE, 0),
            mirrored = prefs.getBoolean(Constants.KEY_IS_MIRRORED, false),
            fitMode = prefs.getString(Constants.KEY_FIT_MODE, Constants.FIT_MODE_FIT)
                ?: Constants.FIT_MODE_FIT,
            brightness = prefs.getFloat(Constants.KEY_BRIGHTNESS, 0f),
            contrast = prefs.getFloat(Constants.KEY_CONTRAST, 0f),
            saturation = prefs.getFloat(Constants.KEY_SATURATION, 0f),
            sharpness = prefs.getFloat(Constants.KEY_SHARPNESS, 0f),
        )
        loadPreview()
        syncAllControls()
    }

    private fun loadPreview() {
        if (currentSourcePath.isBlank() || !File(currentSourcePath).exists()) {
            ivPreview.setImageDrawable(null)
            tvNoPreview.visibility = View.VISIBLE
            btnDeleteMedia.visibility = View.GONE
            btnApply.isEnabled = false
            tvMediaType.text = getString(R.string.label_no_media)
            return
        }

        tvNoPreview.visibility = View.GONE
        btnDeleteMedia.visibility = View.VISIBLE
        btnApply.isEnabled = true
        sliderSharpness.isEnabled = currentIsImage
        tvMediaType.text = if (currentIsImage) {
            getString(R.string.label_photo_ready)
        } else {
            getString(R.string.label_video_ready)
        }

        try {
            if (currentIsImage) {
                ivPreview.setImageBitmap(ImageProcessor.decodeOriented(currentSourcePath, 1280))
            } else {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(currentSourcePath)
                ivPreview.setImageBitmap(retriever.getFrameAtTime(1_000_000))
                retriever.release()
            }
            ivPreview.setTransformState(state)
            applyPreviewColorFilter()
        } catch (_: Throwable) {
            ivPreview.setImageDrawable(null)
            tvNoPreview.visibility = View.VISIBLE
        }
    }

    private fun applyCurrentMedia() {
        if (currentSourcePath.isBlank() || !File(currentSourcePath).exists()) {
            toast(getString(R.string.toast_select_media_first))
            return
        }
        saveState()
        if (currentIsImage) {
            preparePhotoForCamera(showSuccessToast = true)
        } else {
            saveMediaPath(currentSourcePath)
            toast(getString(R.string.toast_settings_saved))
        }
    }

    private fun preparePhotoForCamera(showSuccessToast: Boolean) {
        val sourcePath = currentSourcePath
        val sharpness = state.sharpness
        btnApply.isEnabled = false
        tvMediaType.text = getString(R.string.label_processing_photo)

        worker.execute {
            try {
                val oriented = ImageProcessor.decodeOriented(sourcePath, 2048)
                    ?: throw IllegalStateException("No se pudo leer la fotografía")
                val sharpened = ImageProcessor.sharpen(oriented, sharpness)
                val prepared = File(filesDir, PREPARED_IMAGE_NAME)
                FileOutputStream(prepared).use { stream ->
                    sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
                }
                if (sharpened !== oriented) sharpened.recycle()
                oriented.recycle()

                runOnUiThread {
                    val output = File(filesDir, OUTPUT_VIDEO_NAME)
                    MediaConverter.convertImageToMp4(
                        this,
                        Uri.fromFile(prepared),
                        output,
                    ) { success ->
                        runOnUiThread {
                            btnApply.isEnabled = true
                            tvMediaType.text = getString(R.string.label_photo_ready)
                            if (success) {
                                saveMediaPath(output.absolutePath)
                                if (showSuccessToast) toast(getString(R.string.toast_settings_saved))
                            } else {
                                toast(getString(R.string.toast_conversion_failed))
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    btnApply.isEnabled = true
                    tvMediaType.text = getString(R.string.label_photo_ready)
                    toast(getString(R.string.toast_media_import_failed, e.message ?: "error"))
                }
            }
        }
    }

    private fun saveMediaPath(path: String) {
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
            putString(Constants.KEY_MEDIA_PATH, path)
        }
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
        if (::sliderScaleX.isInitialized) sliderScaleX.value = state.scaleX.coerceIn(0.25f, 4f)
        if (::sliderScaleY.isInitialized) sliderScaleY.value = state.scaleY.coerceIn(0.25f, 4f)
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
        if (!::tvScaleX.isInitialized) return
        tvScaleX.text = getString(R.string.label_width_value, state.scaleX)
        tvScaleY.text = getString(R.string.label_height_value, state.scaleY)
        tvBrightness.text = getString(R.string.label_brightness_value, (state.brightness * 100).toInt())
        tvContrast.text = getString(R.string.label_contrast_value, (state.contrast * 100).toInt())
        tvSaturation.text = getString(R.string.label_saturation_value, state.saturation.toInt())
        tvSharpness.text = getString(R.string.label_sharpness_value, (state.sharpness * 100).toInt())
    }

    private fun updateMirrorButton() {
        val active = MaterialColors.getColor(btnMirror, androidx.appcompat.R.attr.colorPrimary)
        val inactive = MaterialColors.getColor(
            btnMirror,
            com.google.android.material.R.attr.colorSecondaryContainer,
        )
        btnMirror.setBackgroundColor(if (state.mirrored) active else inactive)
    }

    private fun deleteMedia() {
        deleteInternalMediaFiles()
        currentSourcePath = ""
        currentIsImage = false
        state = TransformState()
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit {
            clear()
        }
        ivPreview.setImageDrawable(null)
        ivPreview.clearColorFilter()
        tvNoPreview.visibility = View.VISIBLE
        btnDeleteMedia.visibility = View.GONE
        btnApply.isEnabled = false
        tvMediaType.text = getString(R.string.label_no_media)
        syncAllControls()
        toast(getString(R.string.toast_media_removed))
    }

    private fun deleteInternalMediaFiles() {
        filesDir.listFiles()?.filter {
            it.name.startsWith(SOURCE_PREFIX) ||
                it.name == OUTPUT_VIDEO_NAME ||
                it.name == PREPARED_IMAGE_NAME ||
                it.name.startsWith("virtual.")
        }?.forEach { it.delete() }
    }

    private fun updateModuleStatusUI() {
        val officialScope = getOfficialScope().filter { it != packageName }
        val isActive = (mXposedService != null && officialScope.isNotEmpty()) || checkSelfActive()
        if (isActive) {
            tvModuleStatus.text = getString(R.string.status_module_active)
            val active = MaterialColors.getColor(tvModuleStatus, androidx.appcompat.R.attr.colorPrimary)
            tvModuleStatus.setTextColor(active)
            cardModuleStatus.strokeColor = active
            refreshLSPosedScope()
        } else {
            tvModuleStatus.text = getString(R.string.status_module_inactive)
            val inactive = MaterialColors.getColor(
                tvModuleStatus,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            val stroke = MaterialColors.getColor(
                tvModuleStatus,
                com.google.android.material.R.attr.colorOutline,
            )
            tvModuleStatus.setTextColor(inactive)
            cardModuleStatus.strokeColor = stroke
            cardScopedApps.visibility = View.GONE
        }
    }

    private fun checkSelfActive(): Boolean = false

    private fun getOfficialScope(): List<String> {
        return try {
            mXposedService?.scope ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refreshLSPosedScope() {
        layoutScopedApps.removeAllViews()
        val officialScope = getOfficialScope().filter { it != packageName }
        if (officialScope.isEmpty()) {
            cardScopedApps.visibility = View.GONE
            return
        }
        officialScope.forEach { addAppIconToLayout(it) }
        cardScopedApps.visibility = View.VISIBLE
    }

    private fun addAppIconToLayout(pkgName: String) {
        try {
            val icon = packageManager.getApplicationIcon(pkgName)
            val size = (40 * resources.displayMetrics.density).toInt()
            val image = ImageView(this).apply {
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(0, 0, 16, 0)
                layoutParams = lp
                setImageDrawable(icon)
                contentDescription = pkgName
                setOnClickListener { toast(pkgName) }
            }
            layoutScopedApps.addView(image)
        } catch (_: Exception) {
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
