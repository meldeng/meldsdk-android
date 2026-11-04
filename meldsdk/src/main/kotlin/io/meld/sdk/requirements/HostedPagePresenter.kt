package io.meld.sdk.requirements

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.meld.sdk.hosted.Cancellable
import java.net.URI
import java.net.URISyntaxException

/** Opens a hosted page (KYC, onboarding, terms) outside the app and reports when the customer is back. */
internal interface HostedPagePresenter {
    /** Whether anything on this device can open an https page. */
    val available: Boolean

    /** Opens [url], or returns null when nothing could. [onClosed] runs on main once the customer is back. */
    fun present(url: String, onClosed: () -> Unit): Cancellable?

    /** Runs [then] on main at the host's next resume, or returns null when the host is resumed now. */
    fun whenResumed(then: () -> Unit): Cancellable?
}

/** Starts the browser for a page. A seam, so JVM tests present without an Android framework. */
internal interface BrowserLauncher {
    fun canOpen(): Boolean

    fun open(url: String): Boolean
}

/**
 * The page opens in a Custom Tab, or in the browser when none offers Custom Tabs. Neither can be closed
 * by the app, so the page counts as closed when [lifecycle] resumes after pausing for it.
 */
internal class BrowserPresenter(
    private val lifecycle: Lifecycle,
    private val launcher: BrowserLauncher,
) : HostedPagePresenter {
    constructor(activity: ComponentActivity) : this(activity.lifecycle, AndroidBrowserLauncher(activity))

    override val available: Boolean get() = launcher.canOpen()

    override fun present(url: String, onClosed: () -> Unit): Cancellable? {
        val watcher = ReturnWatcher(lifecycle, onClosed)
        if (!launcher.open(url)) {
            watcher.cancel()
            return null
        }
        return watcher
    }

    override fun whenResumed(then: () -> Unit): Cancellable? =
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) null else ReturnWatcher(lifecycle, then)
}

/**
 * Calls [onReturned] once, at the first resume after a pause, or at the next resume of a host that is not resumed
 * now. Adding it to a resumed host replays a resume, which it ignores.
 */
internal class ReturnWatcher(private val lifecycle: Lifecycle, private val onReturned: () -> Unit) :
    LifecycleEventObserver, Cancellable {
    private var left = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var done = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> left = true
            Lifecycle.Event.ON_RESUME -> if (left && !done) {
                cancel()
                onReturned()
            }
            Lifecycle.Event.ON_DESTROY -> cancel()
            else -> Unit
        }
    }

    override fun cancel() {
        done = true
        lifecycle.removeObserver(this)
    }
}

internal class AndroidBrowserLauncher(private val activity: Activity) : BrowserLauncher {
    override fun canOpen(): Boolean = view(Uri.parse(PROBE_URL)).resolveActivity(activity.packageManager) != null

    override fun open(url: String): Boolean = try {
        val uri = Uri.parse(url)
        val customTabs = CustomTabsClient.getPackageName(activity, null)
        if (customTabs != null) {
            CustomTabsIntent.Builder().build().also { it.intent.setPackage(customTabs) }.launchUrl(activity, uri)
        } else {
            activity.startActivity(view(uri))
        }
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)

    private companion object {
        const val PROBE_URL = "https://meld.io"
    }
}

/** An absolute https URL with a host and no credentials: the only pages the SDK opens. */
internal fun isHttpsPage(url: String): Boolean = try {
    val uri = URI(url)
    uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
} catch (_: URISyntaxException) {
    false
}
