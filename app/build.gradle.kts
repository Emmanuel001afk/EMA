plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.ema.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ema.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("EMA_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("EMA_VERSION_NAME").orNull ?: "0.9.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
