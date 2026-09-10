package com.hazbu.xcam

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.google.android.material.slider.Slider

/**
 * Small compatibility layer for VAGUER Cam's editor.
 *
 * Pinch-to-zoom produces arbitrary floating-point scale values. Material
 * sliders configured with a discrete step reject values that do not land on an
 * exact tick and can throw during layout. The editor intentionally uses
 * continuous sliders, so normalize every slider to stepSize=0 before the first
 * layout pass.
 */
class VaguerApplication : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        makeSlidersContinuous(content)
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
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
