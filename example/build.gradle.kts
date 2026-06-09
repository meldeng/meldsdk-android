import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Credentials — the .env / Secrets.xcconfig equivalent. Read from local.properties (gitignored)
// or an env var of the same name; never hardcoded. See README.
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String = System.getenv(key) ?: secrets.getProperty(key) ?: ""

android {
    namespace = "io.meld.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.meld.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "MELD_API_KEY", "\"${secret("MELD_API_KEY")}\"")
        buildConfigField("String", "MELD_CUSTOMER_ID", "\"${secret("MELD_CUSTOMER_ID")}\"")
        buildConfigField("String", "MELD_API_HOST", "\"${secret("MELD_API_HOST")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":meldsdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
}
