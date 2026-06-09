// Root build. Plugins are declared here (apply false) and applied in module builds.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
