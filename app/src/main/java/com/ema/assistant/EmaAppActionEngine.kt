package com.ema.assistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper

/**
 * Generic application action layer.
 *
 * Strategy:
 * 1. Prefer Android intents/media actions.
 * 2. Fall back to launching the selected application.
 * 3. Use EMA Accessibility for UI actions that the target application exposes.
 *
 * No application-specific provider SDKs are required here.
 */
class EmaAppActionEngine(private val context: Context) {
    private val pm = context.packageManager
    private val main = Handler(Looper.getMainLooper())

    data class AppTarget(
        val label: String,
        val packageName: String
    )

    fun findApp(name: String): AppTarget? {
        val wanted = name.trim()
        if (wanted.isBlank()) return null

        val candidates = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )

        val exact = candidates.firstOrNull {
            it.loadLabel(pm).toString().equals(wanted, ignoreCase = true)
        }
        val partial = candidates.firstOrNull {
            it.loadLabel(pm).toString().contains(wanted, ignoreCase = true)
        }
        val match = exact ?: partial ?: return null

        return AppTarget(
            label = match.loadLabel(pm).toString(),
            packageName = match.activityInfo.packageName
        )
    }

    fun openApp(name: String): AppTarget? {
        val target = findApp(name) ?: return null
        val launch = pm.getLaunchIntentForPackage(target.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return target
    }

    /**
     * Ask the installed application to play media from a natural-language
     * search query using Android's standard media voice-action contracts.
     */
    fun playMediaInApp(appName: String, query: String): Boolean {
        val target = findApp(appName) ?: return false
        if (query.isBlank()) return false

        val intents = listOf(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                setPackage(target.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(MediaStore.INTENT_ACTION_VIDEO_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                setPackage(target.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in intents) {
            if (intent.resolveActivity(pm) != null) {
                runCatching { context.startActivity(intent) }.onSuccess { return true }
            }
        }

        return false
    }

    fun openAndSearchThenTapFirst(appName: String, query: String, done: (Boolean, String) -> Unit) {
        val target = openApp(appName)
        if (target == null) { done(false, "I couldn't find or open " + appName + " on this phone."); return }
        EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
            if (!searched) { done(false, "I opened " + target.label + ", but couldn't access its search field."); return@searchInPackage }
            main.postDelayed({
                val tapped = EmaAccessibilityService.tapFirstResult()
                done(tapped, if (tapped) "I searched " + target.label + " for " + query + " and selected the first result." else "I searched " + target.label + ", but couldn't identify the first selectable result.")
            }, 900L)
        }
    }
    /**
     * Open an application and ask Accessibility to search its visible UI.
     * Accessibility observes the target package and completes the search once
     * an editable/search node becomes available.
     */
    fun openAndSearch(appName: String, query: String, done: (Boolean, String) -> Unit) {
        val target = openApp(appName)
        if (target == null) {
            done(false, "I couldn't find or open $appName on this phone.")
            return
        }

        EmaAccessibilityService.searchInPackage(
            packageName = target.packageName,
            query = query
        ) { success ->
            done(
                success,
                if (success) "I searched ${target.label} for $query."
                else "I opened ${target.label}, but its search field was not accessible."
            )
        }
    }

    /**
     * Open an app, search, wait for its new UI, then try to activate a named
     * result. This remains generic and does not depend on a provider SDK.
     */
    fun openAndSearchThenTap(
        appName: String,
        query: String,
        tapText: String,
        done: (Boolean, String) -> Unit
    ) {
        val target = openApp(appName)
        if (target == null) {
            done(false, "I couldn't find or open $appName on this phone.")
            return
        }

        EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
            if (!searched) {
                done(false, "I opened ${target.label}, but couldn't access its search field.")
                return@searchInPackage
            }

            main.postDelayed({
                val tapped = EmaAccessibilityService.tapText(tapText)
                done(
                    tapped,
                    if (tapped) "I searched ${target.label} and selected $tapText."
                    else "I searched ${target.label}, but couldn't select $tapText from the results."
                )
            }, 900L)
        }
    }
}
