plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

/**
 * Central Hub endpoint the AI layer talks to.
 *
 * Overridable per-build with `-PemaHubUrl=...` (or the `EMA_HUB_URL` environment
 * variable) so a fork can point at its own Hub without editing source. Users can
 * still change it at runtime from the app's settings.
 */
val emaHubUrl: String = (findProperty("emaHubUrl") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("EMA_HUB_URL").orNull?.takeIf { it.isNotBlank() }
    ?: "https://qnogfzwmdjggqtdrkzpv.supabase.co/functions/v1/ai-tunnel"

android {
    namespace = "com.ema.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ema.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("EMA_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("EMA_VERSION_NAME").orNull ?: "0.9.0"
        buildConfigField("String", "EMA_HUB_URL", "\"${emaHubUrl.replace("\"", "\\\"")}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        // Unit tests are pure JVM: no Robolectric, no device. They cover the
        // command grammar, app resolver, send-control resolver, time parser and
        // the native fallback interpreter.
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            (test as? org.gradle.api.tasks.testing.Test)?.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
    buildTypes {
        release {
            // Shrink only the release APK. Debug remains unchanged for development/diagnostics.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    testImplementation("junit:junit:4.13.2")
}
