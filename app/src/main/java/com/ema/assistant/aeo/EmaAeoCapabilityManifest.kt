package com.ema.assistant.aeo

import com.ema.assistant.EmaCapabilityRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Projects EMA's existing local capability catalogue into UCCS discovery metadata.
 *
 * The manifest advertises descriptions and prerequisites only. It does not make
 * any capability remotely invocable, expose a device endpoint, or grant access.
 * EMA continues to execute commands through its own local command pipeline.
 */
object EmaAeoCapabilityManifest {
    const val PROTOCOL = "uccs/1"
    const val TYPE = "aeo.capability-manifest/1"
    const val SOURCE_REGISTRY = "ema-capability-registry/1"
    private const val MAX_LIFETIME_SECONDS = 5 * 60L

    fun create(
        platformVersion: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000
    ): JSONObject {
        require(platformVersion.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
            "EMA platform version must be semantic version x.y.z"
        }
        require(nowSeconds > 0) { "Invalid manifest generation time" }

        val entries = JSONArray()
        EmaCapabilityRegistry.ALL.forEach { capability ->
            val prerequisites = JSONArray()
            capability.requires.sortedBy { it.name }.forEach { prerequisites.put(it.name.lowercase()) }
            entries.put(JSONObject()
                .put("id", capability.id)
                .put("title", capability.title)
                .put("sourceRegistry", SOURCE_REGISTRY)
                .put("sourceCapabilityId", capability.id)
                .put("prerequisites", prerequisites)
                .put("consequential", capability.consequential))
        }

        return JSONObject()
            .put("protocol", PROTOCOL)
            .put("type", TYPE)
            .put("ecosystem", "AEO")
            .put("platform", JSONObject()
                .put("id", "com.ema.assistant")
                .put("name", "EMA")
                .put("version", platformVersion))
            .put("generatedAt", nowSeconds)
            .put("expiresAt", nowSeconds + MAX_LIFETIME_SECONDS)
            .put("capabilities", entries)
    }

    /** Structural validation only; never treat a manifest as an authorization grant. */
    fun validate(manifest: JSONObject, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        return runCatching {
            require(manifest.getString("protocol") == PROTOCOL)
            require(manifest.getString("type") == TYPE)
            require(manifest.getString("ecosystem") == "AEO")
            val platform = manifest.getJSONObject("platform")
            require(platform.getString("id") == "com.ema.assistant")
            require(platform.getString("name") == "EMA")
            require(platform.getString("version").matches(Regex("\\d+\\.\\d+\\.\\d+")))
            val generatedAt = manifest.getLong("generatedAt")
            val expiresAt = manifest.getLong("expiresAt")
            require(generatedAt <= nowSeconds + 60 && expiresAt > nowSeconds)
            require(expiresAt > generatedAt && expiresAt - generatedAt <= MAX_LIFETIME_SECONDS)
            val entries = manifest.getJSONArray("capabilities")
            require(entries.length() == EmaCapabilityRegistry.ALL.size)
            val expected = EmaCapabilityRegistry.ALL.associateBy { it.id }
            val seen = mutableSetOf<String>()
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                require(seen.add(id))
                val source = expected[id] ?: error("Unknown EMA capability")
                require(entry.getString("title") == source.title)
                require(entry.getString("sourceRegistry") == SOURCE_REGISTRY)
                require(entry.getString("sourceCapabilityId") == id)
                require(entry.getBoolean("consequential") == source.consequential)
                val prerequisites = entry.getJSONArray("prerequisites")
                val expectedPrerequisites = source.requires.map { it.name.lowercase() }.sorted()
                require(prerequisites.length() == expectedPrerequisites.size)
                for (prerequisiteIndex in expectedPrerequisites.indices) {
                    require(prerequisites.getString(prerequisiteIndex) == expectedPrerequisites[prerequisiteIndex])
                }
            }
            true
        }.getOrDefault(false)
    }
}
