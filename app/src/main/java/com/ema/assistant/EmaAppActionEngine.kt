package com.ema.assistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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

        val candidates = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
        }.getOrElse {
            android.util.Log.e("EMA", "Generic app discovery failed for '$wanted'", it)
            return null
        }

        val exact = candidates.firstOrNull {
            it.activityInfo?.applicationInfo?.loadLabel(pm)?.toString()?.equals(wanted, ignoreCase = true) == true
        }
        val partial = candidates.firstOrNull {
            it.activityInfo?.applicationInfo?.loadLabel(pm)?.toString()?.contains(wanted, ignoreCase = true) == true
        }
        val match = exact ?: partial ?: return null

        val activityInfo = match.activityInfo ?: return null
        return AppTarget(
            label = activityInfo.applicationInfo?.loadLabel(pm)?.toString().orEmpty(),
            packageName = activityInfo.packageName
        )
    }

    fun openApp(name: String): AppTarget? {
        val wanted = name.trim()
        if (wanted.equals("default browser", true) ||
            wanted.equals("browser", true) ||
            wanted.equals("default web browser", true)
        ) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = intent.resolveActivity(pm) ?: return null
            val appInfo = runCatching { pm.getApplicationInfo(resolved.packageName, 0) }.getOrNull() ?: return null
            val label = pm.getApplicationLabel(appInfo).toString()
            return runCatching {
                context.startActivity(intent)
                AppTarget(label = label, packageName = resolved.packageName)
            }.getOrElse {
                android.util.Log.e("EMA", "Could not launch default browser", it)
                null
            }
        }

        val target = findApp(wanted) ?: return null
        val launch = pm.getLaunchIntentForPackage(target.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            target
        }.getOrElse {
            android.util.Log.e("EMA", "Could not launch " + target.label, it)
            null
        }
    }

    /**
     * Ask the installed application to play media from a natural-language
     * search query using Android's standard media voice-action contracts.
     */
    fun playMediaInApp(appName: String, query: String, done: (Boolean, String) -> Unit) {
        val target = findApp(appName)
        if (target == null) {
            done(false, "I couldn't find $appName on this phone.")
            return
        }
        if (query.isBlank()) {
            done(false, "Tell me what to play.")
            return
        }

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
                val started = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (started) {
                    main.postDelayed({
                        ensureMediaPlaying(target.packageName, appName, query) { playing, message ->
                            if (playing) {
                                done(true, message)
                            } else {
                                // A media intent can launch the app without actually
                                // resolving the requested item. Fall back to the same
                                // generic open -> search -> select -> play path.
                                fallbackSearchAndPlay(target, appName, query, done)
                            }
                        }
                    }, 900L)
                    return
                }
            }
        }

        val opened = openApp(target.label)
        if (opened == null) {
            done(false, "I couldn't open $appName.")
            return
        }
        EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
            if (!searched) {
                done(false, "I opened $appName, but its search field was not accessible.")
                return@searchInPackage
            }
            main.postDelayed({
                val selected = EmaAccessibilityService.tapFirstResult(query)
                if (!selected) {
                    done(false, "I searched $appName for $query, but could not select a result.")
                    return@postDelayed
                }
                main.postDelayed({
                    ensureMediaPlaying(target.packageName, appName, query, done)
                }, 500L)
            }, 1000L)
        }
    }

    fun openAndSearchThenTapFirst(appName: String, query: String, done: (Boolean, String) -> Unit) {
        val target = openApp(appName)
        if (target == null) { done(false, "I couldn't find or open " + appName + " on this phone."); return }
        EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
            if (!searched) { done(false, "I opened " + target.label + ", but couldn't access its search field."); return@searchInPackage }
            main.postDelayed({
                val tapped = EmaAccessibilityService.tapFirstResult(query)
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
        val wanted = appName.trim()
        if (wanted.equals("default browser", true) ||
            wanted.equals("browser", true) ||
            wanted.equals("default web browser", true)
        ) {
            searchWithDefaultBrowser(query, done)
            return
        }

        val target = openApp(wanted)
        if (target == null) {
            done(false, "I couldn't find or open $appName on this phone.")
            return
        }

        EmaAccessibilityService.searchInPackage(
            packageName = target.packageName,
            query = query
        ) { success ->
            if (!success) {
                done(false, "I opened " + target.label + ", but its search controls were not accessible.")
                return@searchInPackage
            }
            main.postDelayed({
                val ui = EmaAccessibilityService.inspectCurrentUi(12000)
                val foundQuery = EmaExecutionVerifier.queryVisible(ui, query)
                done(
                    foundQuery,
                    if (foundQuery) "I searched " + target.label + " for " + query + "."
                    else "I opened " + target.label + " and submitted the search, but could not verify results for " + query + "."
                )
            }, 650L)
        }
    }

    private fun searchWithDefaultBrowser(query: String, done: (Boolean, String) -> Unit) {
        if (query.isBlank()) {
            done(false, "Tell me what to search for.")
            return
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching {
            intent.resolveActivity(pm) ?: throw IllegalStateException("No browser")
            context.startActivity(intent)
            done(true, "Searching the default browser for " + query + ".")
        }.onFailure {
            done(false, "I could not open the default browser.")
        }
    }

    fun sendMessage(
        appName: String,
        recipientName: String,
        phone: String?,
        email: String?,
        body: String,
        done: (Boolean, String) -> Unit
    ) {
        val target = findApp(appName)
        if (target == null) {
            done(false, "I couldn't find $appName on this phone.")
            return
        }
        if (body.isBlank()) {
            done(false, "The message is empty.")
            return
        }

        val label = target.label.lowercase()
        val packageName = target.packageName

        val intent = when {
            label.contains("whatsapp") && !phone.isNullOrBlank() -> {
                val digits = phone.filter { it.isDigit() }
                if (digits.isBlank()) null else Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}")
                ).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            (label.contains("gmail") || label.contains("email") || label.contains("mail")) && !email.isNullOrBlank() -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:${Uri.encode(email)}")
                    setPackage(packageName)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            !phone.isNullOrBlank() && (
                label.contains("message") || label.contains("sms") ||
                    label.contains("messenger") || label.contains("text")
                ) -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${Uri.encode(phone)}")
                    setPackage(packageName)
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(packageName)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

        if (intent == null || intent.resolveActivity(pm) == null) {
            composeThroughAccessibleUi(target, recipientName, body, done)
            return
        }

        runCatching { context.startActivity(intent) }.onSuccess {
            main.postDelayed({
                val screen = EmaAccessibilityService.currentScreenText(8000).lowercase()
                val recipientVerified = EmaExecutionVerifier.recipientVisible(screen, recipientName)
                if (!recipientVerified) {
                    composeThroughAccessibleUi(target, recipientName, body, done)
                    return@postDelayed
                }
                EmaAccessibilityService.tapSendButtonWithVisualFallback(packageName) { pressed ->
                    if (!pressed) {
                        composeThroughAccessibleUi(target, recipientName, body, done)
                        return@tapSendButtonWithVisualFallback
                    }
                    main.postDelayed({
                        val remaining = EmaAccessibilityService.focusedEditableText().trim()
                        done(
                            EmaExecutionVerifier.inputCleared(remaining),
                            if (remaining.isBlank()) "Message sent to " + recipientName + " on " + target.label + "."
                            else "I opened the correct chat for " + recipientName + ", but the message was not verified as sent."
                        )
                    }, 700L)
                }
            }, 800L)
        }.onFailure {
            composeThroughAccessibleUi(target, recipientName, body, done)
        }
    }

    private fun composeThroughAccessibleUi(target: AppTarget, recipientName: String, body: String, done: (Boolean, String) -> Unit) {
        val opened = openApp(target.label)
        if (opened == null) {
            done(false, "I could not open " + target.label + " for " + recipientName + ".")
            return
        }
        main.postDelayed({
            EmaAccessibilityService.searchInPackage(target.packageName, recipientName) { foundRecipient ->
                if (!foundRecipient) {
                    EmaGenericUiController.type(body) { typed, _ ->
                        if (!typed) {
                            done(false, "I opened " + target.label + ", but its recipient/composer controls were not accessible.")
                        } else {
                            main.postDelayed({
                                attemptSendAndVerify(target.packageName, target.label, recipientName, body, 0, done)
                            }, 500L)
                        }
                    }
                    return@searchInPackage
                }
                main.postDelayed({
                    val selected = EmaAccessibilityService.tapTextExact(recipientName)
                    if (!selected) {
                        done(false, "I found the search controls in " + target.label + ", but could not select " + recipientName + ".")
                        return@postDelayed
                    }
                    main.postDelayed({
                        val typed = EmaAccessibilityService.setFocusedText(body)
                        if (!typed) {
                            done(false, "I selected " + recipientName + " in " + target.label + ", but its message field was not accessible.")
                            return@postDelayed
                        }
                        main.postDelayed({
                            EmaAccessibilityService.tapSendButtonWithVisualFallback(target.packageName) { sent ->
                                done(sent, if (sent) "Message sent to " + recipientName + " on " + target.label + "." else "I entered the message for " + recipientName + ", but could not verify Send.")
                            }
                        }, 500L)
                    }, 600L)
                }, 700L)
            }
        }, 900L)
    }
    private fun attemptSendAndVerify(
        packageName: String,
        appLabel: String,
        recipientName: String,
        body: String,
        attempt: Int,
        done: (Boolean, String) -> Unit
    ) {
        EmaAccessibilityService.tapSendButtonWithVisualFallback(packageName) { pressed ->
            if (pressed) {
                main.postDelayed({
                    val composer = EmaAccessibilityService.focusedEditableText().trim()
                    val bodyStillVisible = composer.isNotBlank()
                    if (!bodyStillVisible) {
                        done(true, "Message sent to $recipientName on $appLabel.")
                    } else if (attempt < 2) {
                        attemptSendAndVerify(packageName, appLabel, recipientName, body, attempt + 1, done)
                    } else {
                        done(false, "I pressed Send in $appLabel, but could not verify that the message left the composer.")
                    }
                }, 900L)
            } else if (attempt < 2) {
                main.postDelayed({
                    attemptSendAndVerify(packageName, appLabel, recipientName, body, attempt + 1, done)
                }, 600L)
            } else {
                done(false, "I entered the message in $appLabel, but I could not find or activate its Send control.")
            }
        }
    }

    private fun fallbackSearchAndPlay(
        target: AppTarget,
        appName: String,
        query: String,
        done: (Boolean, String) -> Unit
    ) {
        val opened = openApp(target.label)
        if (opened == null) {
            done(false, "I opened $appName, but could not reopen it for a verified search.")
            return
        }
        main.postDelayed({
            EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
                if (!searched) {
                    done(false, "I opened $appName, but its search field was not accessible.")
                    return@searchInPackage
                }
                main.postDelayed({
                    val selected = EmaAccessibilityService.tapFirstResult(query)
                    if (!selected) {
                        EmaGenericUiController.tap(query) { visualSelected, _ ->
                            if (!visualSelected) {
                                done(false, "I searched $appName for $query, but could not select the requested result.")
                                return@tap
                            }
                            main.postDelayed({
                                ensureMediaPlaying(target.packageName, appName, query, done)
                            }, 700L)
                        }
                        return@postDelayed
                    }
                    main.postDelayed({
                        ensureMediaPlaying(target.packageName, appName, query, done)
                    }, 700L)
                }, 1000L)
            }
        }, 700L)
    }

    private fun ensureMediaPlaying(
        packageName: String,
        appName: String,
        query: String,
        done: (Boolean, String) -> Unit
    ) {
        val ui = EmaAccessibilityService.inspectCurrentUi(12000)
        if (EmaExecutionVerifier.mediaPlaying(ui)) {
            done(true, "Playback is active for $query in $appName.")
            return
        }
        EmaAccessibilityService.activateWithVisualFallback("Play") { activated ->
            if (activated) {
                main.postDelayed({
                    val after = EmaAccessibilityService.inspectCurrentUi(12000)
                    if (EmaExecutionVerifier.mediaPlaying(after)) {
                        done(true, "Playing $query in $appName.")
                    } else {
                        // Some media apps expose no usable Play node. Once the
                        // requested result is selected, use Android's standard
                        // media transport control as a generic final fallback.
                        EmaAccessibilityService.toggleMediaPlayback()
                        main.postDelayed({
                            val verified = EmaExecutionVerifier.mediaPlaying(
                                EmaAccessibilityService.inspectCurrentUi(12000)
                            )
                            done(
                                verified,
                                if (verified) "Playing $query in $appName."
                                else "I activated Play in $appName, but could not verify playback."
                            )
                        }, 700L)
                    }
                }, 600L)
            } else {
                // The requested result may already be selected even when the
                // provider exposes no Play control. Let Android's media
                // transport route attempt playback before declaring failure.
                EmaAccessibilityService.toggleMediaPlayback()
                main.postDelayed({
                    val verified = EmaExecutionVerifier.mediaPlaying(
                        EmaAccessibilityService.inspectCurrentUi(12000)
                    )
                    done(
                        verified,
                        if (verified) "Playing $query in $appName."
                        else "I opened $appName for $query, but could not verify playback."
                    )
                }, 700L)
            }
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
