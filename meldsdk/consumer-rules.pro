# The JavaScript bridge is called from injected JS by reflection; R8 must not strip or rename it.
-keepclassmembers class io.meld.sdk.** {
    @android.webkit.JavascriptInterface <methods>;
}
