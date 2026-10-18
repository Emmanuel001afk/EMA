package com.ema.assistant

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Ships inside the APK. Preparation uses only EMA's assets/private storage. */
object EmaWakeModel {
    private const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private const val ROOT = "vosk-model-small-en-us-0.15/"
    private val required = listOf("am/final.mdl", "conf/mfcc.conf", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst")
    fun directory(context: Context) = File(context.noBackupFilesDir, "wake-model-en-us-0.15")
    fun ready(context: Context): Boolean {
        val dir = directory(context)
        return File(dir, ".model-version").let { it.isFile && it.readText() == SHA256 } &&
            required.all { File(dir, it).isFile }
    }

    /** Worker-thread-only, idempotent and crash-recoverable. Never needs a network. */
    @Synchronized fun prepare(context: Context): File {
        if (ready(context)) return directory(context)
        val staging = File(context.noBackupFilesDir, "wake-model-preparing")
        val archive = File(context.noBackupFilesDir, "wake-model-asset.tmp")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare built-in wake model storage" }
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            context.assets.open("ema-wake-model.zip").use { input ->
                archive.outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        check(size <= 45L * 1024 * 1024) { "Invalid bundled model size" }
                        hash.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(hash.digest().joinToString("") { "%02x".format(it) } == SHA256) { "Built-in wake model integrity check failed" }
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var expanded = 0L
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(++count <= 5000 && entry.name.startsWith(ROOT)) { "Invalid built-in model entry" }
                    val relative = entry.name.removePrefix(ROOT)
                    if (relative.isEmpty()) { zip.closeEntry(); continue }
                    val target = File(staging, relative).canonicalFile
                    check(target.path.startsWith(staging.canonicalPath + File.separator)) { "Unsafe bundled model path" }
                    if (entry.isDirectory) check(target.isDirectory || target.mkdirs()) else {
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                expanded += read
                                check(expanded <= 256L * 1024 * 1024) { "Built-in model exceeds storage limit" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            check(required.all { File(staging, it).isFile }) { "Built-in wake model is incomplete" }
            org.vosk.Model(staging.path).close()
            File(staging, ".model-version").writeText(SHA256)
            val destination = directory(context)
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Could not activate built-in wake model" }
            // Remove the obsolete manually-imported model from previous builds.
            File(context.noBackupFilesDir, "wake-model").deleteRecursively()
            return destination
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }
}
