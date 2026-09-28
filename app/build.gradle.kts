plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.ema.assistant"
    compileSdk = 35
    defaultConfig { applicationId = "com.ema.assistant"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n        targetCompatibility = JavaVersion.VERSION_17\n    }\n    kotlinOptions { jvmTarget = "17" }\n    buildFeatures { buildConfig = true }
}
dependencies { implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.activity:activity-ktx:1.10.1") }
