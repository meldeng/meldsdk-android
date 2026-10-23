package io.meld.sdk.hosted

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * An invisible container for a provider page that presents its own payment sheet. The page must be
 * attached and visible for its sheet to open, but the customer should only ever see the sheet, so it
 * is hosted full-size over the host Activity's content at zero alpha and lets every touch through.
 *
 * Mounted from a host view, that view only locates the Activity and the surface ends when it leaves
 * its window. Mounted from the Activity, the surface ends when the Activity is destroyed. Main thread.
 */
internal class OffscreenSurfaceHost(
    override val container: ViewGroup,
    override val foreground: ActivityForeground,
    private val host: View?,
) : HostedSurface {
    override var onHostGone: (() -> Unit)? = null

    private val watcher = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            onHostGone?.invoke()
        }
    }

    init {
        if (host == null) {
            foreground.onDestroyed = { onHostGone?.invoke() }
        } else {
            host.addOnAttachStateChangeListener(watcher)
        }
    }

    override fun remove() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { remove() }
            return
        }
        onHostGone = null
        host?.removeOnAttachStateChangeListener(watcher)
        foreground.close()
        (container.parent as? ViewGroup)?.removeView(container)
    }

    private class PassThroughFrame(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

        override fun dispatchGenericMotionEvent(event: MotionEvent?): Boolean = false
    }

    companion object {
        /** Ends when [host] leaves its window. Null off the main thread, or when the host is not in an Activity on screen. */
        fun attach(host: View): OffscreenSurfaceHost? {
            if (Looper.myLooper() != Looper.getMainLooper()) return null
            return findActivity(host.context)?.let { attach(it, host) }
        }

        /** Ends when [activity] is destroyed. Null off the main thread, or when [activity] is not on screen. */
        fun attach(activity: Activity): OffscreenSurfaceHost? {
            if (Looper.myLooper() != Looper.getMainLooper()) return null
            return attach(activity, null)
        }

        private fun attach(activity: Activity, host: View?): OffscreenSurfaceHost? {
            if (activity.isFinishing || activity.isDestroyed) return null
            val content = activity.findViewById<ViewGroup?>(android.R.id.content) ?: return null
            if (!content.isAttachedToWindow || content.windowVisibility != View.VISIBLE) return null
            val container = PassThroughFrame(activity).apply {
                alpha = 0f
                visibility = View.VISIBLE
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            content.addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return OffscreenSurfaceHost(container, ActivityForeground(activity), host)
        }
    }
}

internal fun findActivity(of: Context?): Activity? {
    var context = of
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Follows the host Activity's pause, resume and destruction. Mounted from the customer's tap, so it starts resumed. */
internal class ActivityForeground(
    private val activity: Activity,
    private val application: Application = activity.application,
) : HostForeground, Application.ActivityLifecycleCallbacks {
    override var resumed = true
        private set

    var onDestroyed: (() -> Unit)? = null

    private val listeners = mutableListOf<Pair<() -> Unit, () -> Unit>>()

    init {
        application.registerActivityLifecycleCallbacks(this)
    }

    fun close() {
        listeners.clear()
        onDestroyed = null
        application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun observe(onPaused: () -> Unit, onResumed: () -> Unit): Cancellable {
        val listener = onPaused to onResumed
        listeners += listener
        return Cancellable { listeners -= listener }
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity !== this.activity) return
        resumed = false
        listeners.toList().forEach { it.first() }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !== this.activity) return
        resumed = true
        listeners.toList().forEach { it.second() }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (activity !== this.activity) return
        onDestroyed?.invoke()
    }
}
