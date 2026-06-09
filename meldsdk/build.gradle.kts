plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "io.meld.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Expose a single 'release' variant for publishing, with sources for consumers.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests {
            // MeldOrder.fromJson uses org.json, which is a stub in unit tests; the real impl is
            // provided via the org.json test dependency below.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// Maven coordinates: io.meld:meldsdk. JitPack consumes this via publishToMavenLocal.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.meld"
            artifactId = "meldsdk"
            version = "0.1.0"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
