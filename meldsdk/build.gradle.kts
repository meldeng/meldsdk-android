import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
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

// Publishing to Maven Central via the Sonatype Central Portal (central.sonatype.com).
// Release-time setup (your side): verify the `io.meld` namespace, then provide via env/CI secrets:
//   ORG_GRADLE_PROJECT_mavenCentralUsername / ...Password  (Central Portal user token)
//   ORG_GRADLE_PROJECT_signingInMemoryKey / ...Password     (GPG private key + passphrase)
// Then: ./gradlew :meldsdk:publishToMavenCentral
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Central requires signed artifacts. Sign only when a key is configured, so the unsigned
    // publishToMavenLocal used by CI and the React Native example keeps working without a key.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }

    // Version is tag-driven in CI (see .github/workflows/release.yml, passes -PmeldsdkVersion=<tag>);
    // the literal fallback is only for local publishToMavenLocal.
    coordinates("io.meld", "meldsdk", (findProperty("meldsdkVersion") as String?) ?: "0.2.0")

    pom {
        name.set("MeldSDK")
        description.set("Embed a crypto on/off-ramp provider widget (Mercuryo card) in your Android app.")
        url.set("https://github.com/meldeng/meldsdk-android")
        licenses {
            license {
                name.set("Proprietary")
                url.set("https://github.com/meldeng/meldsdk-android/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("meld")
                name.set("Meld")
                email.set("support@meld.io")
            }
        }
        scm {
            url.set("https://github.com/meldeng/meldsdk-android")
            connection.set("scm:git:git://github.com/meldeng/meldsdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/meldeng/meldsdk-android.git")
        }
    }
}
