package com.hazbu.xcam

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.slider.Slider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Compatibility and diagnostics layer for the VAGUER Cam editor.
 *
 * It keeps Material sliders continuous and records the last uncaught Java/Kotlin
 * exception. On the next successful launch the diagnostic is copied to the
 * clipboard so it can be pasted directly into the support chat if needed.
 */
class VaguerApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        private const val CRASH_FILE = "vaguer_last_crash.txt"
    }

    private var crashReportedThisProcess = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText(
                    buildString {
                        appendLine("VAGUER Cam crash")
                        appendLine("thread=${thread.name}")
                        appendLine("sdk=${android.os.Build.VERSION.SDK_INT}")
                        appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                        appendLine()
                        append(sw.toString())
                    },
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        makeSlidersContinuous(content)
    }

    override fun onActivityResumed(activity: Activity) {
        if (crashReportedThisProcess) return
        val crash = File(filesDir, CRASH_FILE)
        if (!crash.exists()) return

        runCatching {
            val text = crash.readText().take(30_000)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VAGUER Cam diagnóstico", text))
            crash.delete()
            crashReportedThisProcess = true
            Toast.makeText(
                activity,
                "Se recuperó un diagnóstico del cierre y se copió al portapapeles.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun makeSlidersContinuous(view: View) {
        if (view is Slider) {
            runCatching { view.stepSize = 0f }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                makeSlidersContinuous(view.getChildAt(i))
            }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
