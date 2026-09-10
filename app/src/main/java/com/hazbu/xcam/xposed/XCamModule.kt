package com.hazbu.xcam.xposed

import android.content.Context
import android.database.ContentObserver
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.capture.CaptureManager
import com.hazbu.xcam.core.capture.YuvFrameProcessor
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.orientation.CameraOrientationManager
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.utils.Logger
import com.hazbu.xcam.utils.SystemUtils
import com.hazbu.xcam.utils.UIUtils
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@UnstableApi
class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private var settingsObserver: ContentObserver? = null
    @Volatile private var pendingCameraId: String? = null
    private val ignoreHooks = ThreadLocal.withInitial { false }

    private val injectors = XCamInjectors(this)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine { printLog(it) }
    private val orientationManager = CameraOrientationManager { printLog(it) }
    private val yuvProcessor = YuvFrameProcessor()

    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settings.refreshSettings(it) },
    ) { printLog(it) }

    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settings },
        surfaceManager = surfaceManager,
        mediaEngine = mediaEngine,
        surfaceProvider = surfaceProvider,
        previewRotationProvider = { context -> orientationManager.previewCompensationDegrees(context) },
    ) { printLog(it) }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }

    val mediaPath: String? get() = settings.mediaPath
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        if (tr != null || msg.contains("Error") || msg.contains("failed") || msg.contains("FATAL")) {
            Logger.e(this, msg, tr)
        } else {
            Logger.i(this, msg)
        }
    }

    fun logInit(msg: String) = Logger.i(this, "[INIT] $msg")
    fun logHook(msg: String) = Logger.d(this, "[HOOK] $msg")
    fun showToast(message: String) = UIUtils.showToast(mContext, message) { printLog(it) }
    fun isCapturingState() = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition().toInt() }
    }

    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)
    fun registerImageReaderSurface(s: Surface, f: Int, w: Int, h: Int) =
        surfaceManager.registerImageReaderSurface(s, f, w, h)
    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)
    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)
    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    fun injectYuvFrame(image: android.media.Image, width: Int, height: Int) {
        val jpeg = handleStreamFrame(width, height) ?: return
        yuvProcessor.injectToImage(image, jpeg)
    }

    fun stopEngine() = engine.stop()

    fun updateCamera2Orientation(cameraId: String) {
        pendingCameraId = cameraId
        mContext?.let { context ->
            orientationManager.updateCamera2(context, cameraId)
            engine.refreshActiveOutput()
        }
    }
    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)
    fun handleModernPreview(s: Surface) = engine.handleModernPreview(s)
    fun handleSurfaceViewPreview(h: SurfaceHolder) = engine.handleSurfaceViewPreview(h)
    fun getDummySurface() = engine.getDummySurface()

    fun handleCapture(w: Int, h: Int): ByteArray? {
        mContext?.let { settings.refreshSettings(it) }
        return captureManager.handleCapture(
            path = settings.mediaPath,
            width = w,
            height = h,
            rotationAngle = settings.rotationAngle,
            isMirrored = settings.isMirrored,
            scaleX = settings.scaleX,
            scaleY = settings.scaleY,
            offsetX = settings.offsetX,
            offsetY = settings.offsetY,
            fitMode = settings.fitMode,
            brightness = settings.brightness,
            contrast = settings.contrast,
            saturation = settings.saturation,
            isIgnoringHooks = { isIgnoringHooks() },
            setIgnoringHooks = { setIgnoringHooks(it) },
        )
    }

    fun handleStreamFrame(w: Int, h: Int) = captureManager.handleStreamFrame(
        path = settings.mediaPath,
        width = w,
        height = h,
        rotationAngle = settings.rotationAngle,
        isMirrored = settings.isMirrored,
        scaleX = settings.scaleX,
        scaleY = settings.scaleY,
        offsetX = settings.offsetX,
        offsetY = settings.offsetY,
        fitMode = settings.fitMode,
        brightness = settings.brightness,
        contrast = settings.contrast,
        saturation = settings.saturation,
        outputRotationCompensation =
            mContext?.let { orientationManager.previewCompensationDegrees(it) } ?: 0,
        isIgnoringHooks = { isIgnoringHooks() },
        setIgnoringHooks = { setIgnoringHooks(it) },
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()

        // Never hook VAGUER Cam's own manager/editor process. Some Xposed
        // managers keep self-hooks alive until reboot, which can make the
        // launcher activity crash even after the module is toggled off.
        if (param.packageName == "com.vaguer.cam") return

        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        incrementSessionGeneration()
        clearPreviewSurfaces()
        logInit(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun registerSettingsObserver(context: Context) {
        if (settingsObserver != null) return

        val uri = Uri.parse("content://${Constants.AUTHORITY}")
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    settings.refreshSettings(context)
                    engine.refreshActiveOutput()
                    logInit("Settings refreshed from manager")
                }
            }

        try {
            context.contentResolver.registerContentObserver(uri, true, observer)
            settingsObserver = observer
        } catch (e: Throwable) {
            logInit("Settings observer unavailable: ${e.message}")
        }
    }

    private fun hookContextInit() {
        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    logInit("Context Initialized: ${mContext?.packageName}")
                    mContext?.let {
                        settings.refreshSettings(it)
                        registerSettingsObserver(it)
                        pendingCameraId?.let { cameraId ->
                            orientationManager.updateCamera2(it, cameraId)
                        }
                    }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            logInit("Context hook failure: ${e.message}")
        }
    }
}
