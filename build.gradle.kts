// Root build. Plugins are declared here (apply false) and applied in module builds.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}
