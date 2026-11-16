package com.ema.assistant

import android.os.Environment
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Minimal generic file layer behind EMA's "Broad file access" permission.
 * Traversal is bounded and canonically confined to shared storage so a broad
 * storage scan cannot block the assistant or escape storage roots.
 */
object EmaFileAccessEngine {
    private const val MAX_RESULTS = 40
    private const val MAX_DEPTH = 6

    private val COMMON_AUDIO_PROBE_EXTS = listOf(
        "m4a", "wav", "mp3", "opus", "ogg", "aac", "flac", "amr", "3gp", "webm"
    )

    private val RECORDING_DIR_CANDIDATES = listOf(
        "Recordings",
        "Recordings/Voice Recorder",
        "Voice Recorder",
        "Sounds",
        "Music/Recordings",
        "Music",
        "Download",
        "Documents",
        "Podcasts"
    )

    fun isAvailable(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    fun list(path: String = ""): String {
        if (!isAvailable()) return "EMA needs Broad file access before it can inspect shared storage."
        val root = resolve(path) ?: return "I could not find that storage location."
        if (!root.isDirectory) return "That path is a file, not a folder."
        val files = root.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )?.take(MAX_RESULTS).orEmpty()
        if (files.isEmpty()) return "That folder is empty or EMA could not read its contents."
        return files.joinToString("\n") { file ->
            if (file.isDirectory) "[Folder] ${file.name}" else "[File] ${file.name} (${file.length()} bytes)"
        }
    }

    fun find(name: String): String {
        if (!isAvailable()) return "EMA needs Broad file access before it can search shared storage."
        val query = name.trim().lowercase()
        if (query.isBlank()) return "Tell me the file name to find."
        val base = Environment.getExternalStorageDirectory()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(base to 0)
        val matches = mutableListOf<File>()
        while (queue.isNotEmpty() && matches.size < MAX_RESULTS) {
            val (dir, depth) = queue.removeFirst()
            val children = runCatching { dir.listFiles().orEmpty() }.getOrDefault(emptyArray())
            for (child in children) {
                if (child.name.lowercase().contains(query)) matches += child
                if (child.isDirectory && depth < MAX_DEPTH && !child.isHidden) queue.add(child to depth + 1)
                if (matches.size >= MAX_RESULTS) break
            }
        }
        if (matches.isEmpty()) return "I could not find a matching file in shared storage."
        return matches.joinToString("\n") { it.absolutePath }
    }

    /**
     * Resolve any supported local audio recording (`.wav`, `.m4a`, `.mp3`,
     * `.opus`, `.ogg`, `.flac`, `.aac`, `.amr`, `.3gp`, `.webm`, `.mp4`, `.mkv`)
     * strictly confined within shared storage for offline transcription.
     */
    fun findAudioFile(rawQuery: String): File? {
        if (!isAvailable()) return null
        val cleaned = rawQuery.trim().trim('"', '\'', '.', ' ')
        if (cleaned.isBlank()) return null

        // Direct relative or absolute path confined under shared storage.
        resolve(cleaned)?.takeIf { it.isFile && isSupportedAudio(it.name) }?.let { return it }
        if (!isSupportedAudio(cleaned)) {
            for (ext in COMMON_AUDIO_PROBE_EXTS) {
                resolve("$cleaned.$ext")?.takeIf { it.isFile }?.let { return it }
            }
        }

        val base = Environment.getExternalStorageDirectory().canonicalFile
        val basePrefix = base.path.trimEnd(File.separatorChar) + File.separator
        val cleanedLower = cleaned.lowercase(Locale.ROOT)
        val needle = stripKnownAudioExtension(cleanedLower)
        val priorityRoots = RECORDING_DIR_CANDIDATES
            .map { File(base, it) }
            .filter { it.isDirectory }
        val roots = if (priorityRoots.isNotEmpty()) priorityRoots + base else listOf(base)

        val visited = HashSet<String>()
        var bestPartial: File? = null
        var scannedDirs = 0

        for (root in roots) {
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && scannedDirs < 280) {
                val (dir, depth) = queue.removeFirst()
                val canonicalDir = runCatching { dir.canonicalPath }.getOrNull() ?: continue
                if (canonicalDir != base.path && !canonicalDir.startsWith(basePrefix)) continue
                if (!visited.add(canonicalDir)) continue
                scannedDirs++

                val children = runCatching { dir.listFiles().orEmpty() }.getOrDefault(emptyArray())
                for (child in children) {
                    if (child.isHidden) continue
                    if (child.isFile && isSupportedAudio(child.name)) {
                        val canon = runCatching { child.canonicalFile }.getOrNull() ?: continue
                        if (!canon.path.startsWith(basePrefix)) continue
                        val nameLow = canon.name.lowercase(Locale.ROOT)
                        val stem = canon.nameWithoutExtension.lowercase(Locale.ROOT)
                        if (nameLow == cleanedLower || stem == needle) {
                            return canon
                        }
                        if (bestPartial == null && stem.contains(needle)) {
                            bestPartial = canon
                        }
                    } else if (child.isDirectory && depth < 4) {
                        queue.add(child to depth + 1)
                    }
                }
            }
            if (bestPartial != null && root != base) return bestPartial
        }
        return bestPartial
    }

    /**
     * Find the most recently modified voice recording inside common recording
     * and download folders (confined under shared storage).
     */
    fun findLatestAudioRecording(): File? {
        if (!isAvailable()) return null
        val base = Environment.getExternalStorageDirectory().canonicalFile
        val basePrefix = base.path.trimEnd(File.separatorChar) + File.separator
        val roots = RECORDING_DIR_CANDIDATES
            .map { File(base, it) }
            .filter { it.isDirectory }
        if (roots.isEmpty()) return null

        val visited = HashSet<String>()
        var newest: File? = null
        var newestTime = 0L
        var scannedDirs = 0

        for (root in roots) {
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && scannedDirs < 140) {
                val (dir, depth) = queue.removeFirst()
                val canonicalDir = runCatching { dir.canonicalPath }.getOrNull() ?: continue
                if (canonicalDir != base.path && !canonicalDir.startsWith(basePrefix)) continue
                if (!visited.add(canonicalDir)) continue
                scannedDirs++

                val children = runCatching { dir.listFiles().orEmpty() }.getOrDefault(emptyArray())
                for (child in children) {
                    if (child.isHidden) continue
                    if (child.isFile && isSupportedAudio(child.name) && child.length() > 128L) {
                        val canon = runCatching { child.canonicalFile }.getOrNull() ?: continue
                        if (!canon.path.startsWith(basePrefix)) continue
                        val mtime = canon.lastModified()
                        if (mtime > newestTime) {
                            newestTime = mtime
                            newest = canon
                        }
                    } else if (child.isDirectory && depth < 3) {
                        queue.add(child to depth + 1)
                    }
                }
            }
        }
        return newest
    }

    /** Legacy WAV lookup delegating to [findAudioFile] filtered to RIFF/WAVE extensions. */
    fun findWavFile(rawQuery: String): File? = findAudioFile(rawQuery)

    private fun isSupportedAudio(name: String): Boolean =
        EmaOfflineAudioDecoder.isSupportedAudioFileName(name)

    private fun stripKnownAudioExtension(lowerName: String): String {
        val dot = lowerName.lastIndexOf('.')
        if (dot <= 0 || dot == lowerName.lastIndex) return lowerName
        val ext = lowerName.substring(dot + 1)
        return if (ext in EmaOfflineAudioDecoder.SUPPORTED_EXTENSIONS) lowerName.substring(0, dot) else lowerName
    }

    private fun resolve(path: String): File? {
        val base = Environment.getExternalStorageDirectory().canonicalFile
        val candidate = if (path.isBlank()) base else {
            val raw = File(path)
            if (raw.isAbsolute) raw else File(base, path)
        }.canonicalFile
        val basePath = base.path.trimEnd(File.separatorChar) + File.separator
        return if (candidate.path == base.path || candidate.path.startsWith(basePath)) candidate else null
    }
}
