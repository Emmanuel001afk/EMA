package com.ema.assistant

import android.os.Environment
import java.io.File
import java.util.ArrayDeque

/**
 * Minimal generic file layer behind EMA's "Broad file access" permission.
 * Traversal is bounded so a broad storage scan cannot block the assistant.
 */
object EmaFileAccessEngine {
    private const val MAX_RESULTS = 40
    private const val MAX_DEPTH = 6

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
