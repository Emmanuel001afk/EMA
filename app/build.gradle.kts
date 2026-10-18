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
    // Offline keyword decoding; no cloud key or speech service is used at idle.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    testImplementation("junit:junit:4.13.2")
}

// The model is part of every APK, not an end-user download/import. Keep the
// large binary in generated build storage, never in Git. Both sources must
// produce the same pinned upstream archive or the build fails closed.
val wakeModelSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
val wakeAssets = layout.buildDirectory.dir("generated/wakeAssets")
val prepareBundledWakeModel by tasks.registering {
    val archive = wakeAssets.map { it.file("ema-wake-model.zip") }
    inputs.property("modelSha256", wakeModelSha256)
    outputs.file(archive)
    doLast {
        val target = archive.get().asFile
        fun sha256(file: java.io.File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    hash.update(buffer, 0, read)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (!target.isFile || sha256(target) != wakeModelSha256) {
            target.parentFile.mkdirs()
            val temporary = java.io.File(target.parentFile, "model-download.tmp")
            val sources = listOf(
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "https://github.com/darsantiago/vosk-models-mirror/releases/download/v1/vosk-model-small-en-us-0.15.zip"
            )
            var downloaded = false
            for (source in sources) {
                try {
                    logger.lifecycle("Preparing built-in EMA wake model from $source")
                    val connection = java.net.URI(source).toURL().openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                    }
                    connection.getInputStream().buffered().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 45L * 1024 * 1024) { "Wake model download exceeds expected size" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(sha256(temporary) == wakeModelSha256) { "Wake model SHA-256 mismatch" }
                    java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    downloaded = true
                    break
                } catch (error: Exception) {
                    logger.warn("Wake model source failed: ${error.message}")
                } finally {
                    temporary.delete()
                }
            }
            check(downloaded) { "Cannot package EMA wake model. Restore build network access; no model-less APK will be produced." }
        }
        java.util.zip.ZipFile(target).use { zip ->
            listOf("am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst").forEach {
                check(zip.getEntry("vosk-model-small-en-us-0.15/$it") != null) { "Incomplete bundled wake model: $it" }
            }
        }
    }
}
android.sourceSets.getByName("main").assets.srcDir(wakeAssets)
android.androidResources.noCompress += "zip"
tasks.configureEach {
    if (name.matches(Regex("merge.*Assets"))) dependsOn(prepareBundledWakeModel)
}
