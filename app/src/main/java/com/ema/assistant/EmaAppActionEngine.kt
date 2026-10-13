package com.ema.assistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Generic application action layer.
 *
 * Strategy, in order:
 *   1. Android intents and standard media voice-action contracts.
 *   2. Launching the selected application and driving its visible UI.
 *   3. EMA screen control for controls the application exposes.
 *   4. OCR over a screenshot for controls absent from the node tree.
 *
 * No provider SDK is used and no app is special-cased beyond the deep links that
 * Android itself defines. Everything here is reachable with the AI layer disabled.
 *
 * Two invariants are enforced throughout:
 *   - A body that is already in the composer is never typed a second time, so a
 *     retry cannot append a duplicate message.
 *   - Success is only reported on observable evidence. "The app opened" and
 *     "the message was sent" are different claims and are worded differently.
 */
class EmaAppActionEngine(private val context: Context) {
    private val pm = context.packageManager
    private val main = Handler(Looper.getMainLooper())

    private companion object {
        /** Re-reads allowed while waiting for a deep-linked composer to populate. */
        const val PREFILL_WAIT_ATTEMPTS = 2

        /**
         * Typing attempts before EMA stops and reports. Bounded so an app whose
         * field value cannot be read back produces a specific failure instead of
         * retrying until the command timeout produces a generic one.
         */
        const val MAX_TYPE_ATTEMPTS = 2
    }

    data class AppTarget(
        val label: String,
        val packageName: String
    )

    /** Applications the user can actually launch, cached per engine instance. */
    private var installedCache: List<EmaAppResolver.App>? = null

    private fun installedApps(): List<EmaAppResolver.App> {
        installedCache?.let { return it }
        val apps = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val label = activityInfo.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
                if (label.isBlank()) null else EmaAppResolver.App(label, activityInfo.packageName)
            }.distinctBy { it.packageName }
        }.getOrElse {
            android.util.Log.e("EMA", "Installed application discovery failed", it)
            emptyList()
        }
        installedCache = apps
        return apps
    }

    fun installedAppLabels(): List<String> = installedApps().map { it.label }

    fun findApp(name: String): AppTarget? {
        val wanted = name.trim()
        if (wanted.isBlank()) return null

        // Browsers are addressed by role, not by label, on every device.
        if (EmaAppResolver.normalize(wanted) in setOf("browser", "default browser", "web browser", "default web browser", "internet")) {
            defaultBrowserTarget()?.let { return it }
        }

        val match = EmaAppResolver.resolve(wanted, installedApps())
        if (match != null) return AppTarget(match.app.label, match.app.packageName)

        // Last resort: the legacy substring scan, kept so an unusual launcher
        // label that the resolver scored too low can still be opened.
        val candidates = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        }.getOrElse {
            android.util.Log.e("EMA", "Generic app discovery failed for '$wanted'", it)
            return null
        }
        val normalized = EmaAppResolver.normalize(wanted)
        val fallback = candidates.firstOrNull {
            val label = it.activityInfo?.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
            label.isNotBlank() && EmaAppResolver.normalize(label).contains(normalized)
        } ?: return null
        val activityInfo = fallback.activityInfo ?: return null
        return AppTarget(
            label = activityInfo.applicationInfo?.loadLabel(pm)?.toString().orEmpty(),
            packageName = activityInfo.packageName
        )
    }

    private fun defaultBrowserTarget(): AppTarget? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val resolved = intent.resolveActivity(pm) ?: return null
        val appInfo = runCatching { pm.getApplicationInfo(resolved.packageName, 0) }.getOrNull() ?: return null
        return AppTarget(pm.getApplicationLabel(appInfo).toString(), resolved.packageName)
    }

    fun openApp(name: String): AppTarget? {
        val wanted = name.trim()
        if (EmaAppResolver.normalize(wanted) in setOf("browser", "default browser", "web browser", "default web browser", "internet")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = defaultBrowserTarget() ?: return null
            return runCatching {
                context.startActivity(intent)
                target
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

    // ----------------------------------------------------------------- media

    /**
     * Play media from a natural-language query.
     *
     * Android's standard media voice-action contract is tried first because it
     * is the only route that asks the application itself to resolve and start
     * the item. When the application does not honour it — or honours it without
     * actually starting playback — EMA falls back to the visible path the user
     * described: open the app, open its search, TYPE the query, select the best
     * matching result, then start playback.
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

        val unavailable = EmaAccessibilityService.availabilityProblem()

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
                        if (playbackConfirmed()) {
                            done(true, "Playing $query in $appName.")
                        } else {
                            // The intent launched the app without resolving the
                            // requested item, so fall through to the visible path.
                            searchAndPlay(target, appName, query, unavailable, done)
                        }
                    }, 1200L)
                    return
                }
            }
        }

        searchAndPlay(target, appName, query, unavailable, done)
    }

    /**
     * The visible, provider-neutral playback path: open, search, type, select,
     * play. This is what runs when an app has no media voice-action contract.
     */
    private fun searchAndPlay(
        target: AppTarget,
        appName: String,
        query: String,
        unavailable: String?,
        done: (Boolean, String) -> Unit
    ) {
        if (unavailable != null) {
            val opened = openApp(target.label) != null
            done(
                false,
                if (opened) "I opened $appName, but $unavailable"
                else "I couldn't open $appName. $unavailable"
            )
            return
        }

        val opened = openApp(target.label)
        if (opened == null) {
            done(false, "I couldn't open $appName.")
            return
        }

        main.postDelayed({
            // Search often lives behind its own navigation entry; reveal it
            // before waiting for an editable field that is not on this screen.
            EmaAccessibilityService.revealSearchEntry(target.packageName)
            EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
                if (!searched) {
                    done(false, "I opened $appName, but could not reach its search field to type \"$query\".")
                    return@searchInPackage
                }
                main.postDelayed({
                    val selected = EmaAccessibilityService.tapFirstResult(query)
                    if (!selected) {
                        EmaGenericUiController.tap(query) { visualSelected, _ ->
                            if (!visualSelected) {
                                done(false, "I typed \"$query\" into $appName's search, but could not select a result.")
                                return@tap
                            }
                            main.postDelayed({ startPlayback(target, appName, query, done) }, 900L)
                        }
                        return@postDelayed
                    }
                    main.postDelayed({ startPlayback(target, appName, query, done) }, 900L)
                }, 1200L)
            }
        }, 900L)
    }

    private fun startPlayback(target: AppTarget, appName: String, query: String, done: (Boolean, String) -> Unit) {
        if (playbackConfirmed()) {
            done(true, "Playing $query in $appName.")
            return
        }
        EmaAccessibilityService.activateWithVisualFallback("Play") { activated ->
            main.postDelayed({
                if (playbackConfirmed()) {
                    done(true, "Playing $query in $appName.")
                    return@postDelayed
                }
                // Some media apps expose no usable Play node. Once the requested
                // result is selected, Android's media transport is the generic
                // final route.
                EmaAccessibilityService.mediaTransport(EmaAccessibilityService.MediaTransportAction.PLAY)
                main.postDelayed({
                    val verified = playbackConfirmed()
                    done(
                        verified,
                        when {
                            verified -> "Playing $query in $appName."
                            activated -> "I selected \"$query\" in $appName and pressed Play, but could not confirm audio started."
                            else -> "I selected \"$query\" in $appName, but could not find a Play control to start it."
                        }
                    )
                }, 900L)
            }, 700L)
        }
    }

    /** Playback evidence: Android's audio state first, visible UI second. */
    private fun playbackConfirmed(): Boolean {
        if (EmaAccessibilityService.isAudioPlaying()) return true
        val ui = EmaAccessibilityService.inspectCurrentUi(12000)
        return EmaExecutionVerifier.playbackLikely(ui, false)
    }

    fun openAndSearchThenTapFirst(appName: String, query: String, done: (Boolean, String) -> Unit) {
        val target = openApp(appName)
        if (target == null) { done(false, "I couldn't find or open " + appName + " on this phone."); return }
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            done(false, "I opened " + target.label + ", but $unavailable")
            return
        }
        main.postDelayed({
            EmaAccessibilityService.revealSearchEntry(target.packageName)
            EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
                if (!searched) {
                    done(false, "I opened " + target.label + ", but couldn't reach its search field.")
                    return@searchInPackage
                }
                main.postDelayed({
                    val tapped = EmaAccessibilityService.tapFirstResult(query)
                    done(
                        tapped,
                        if (tapped) "I searched " + target.label + " for " + query + " and selected the first result."
                        else "I searched " + target.label + " for " + query + ", but couldn't identify the first selectable result."
                    )
                }, 1100L)
            }
        }, 800L)
    }

    /**
     * Open an application and search its visible UI. The callback always runs:
     * screen control reports either success or a deadline, never silence.
     */
    fun openAndSearch(appName: String, query: String, done: (Boolean, String) -> Unit) {
        val wanted = appName.trim()
        if (EmaAppResolver.normalize(wanted) in setOf("browser", "default browser", "web browser", "default web browser", "internet")) {
            searchWithDefaultBrowser(query, done)
            return
        }

        val target = openApp(wanted)
        if (target == null) {
            done(false, "I couldn't find or open $appName on this phone.")
            return
        }

        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            done(false, "I opened " + target.label + ", but $unavailable")
            return
        }

        main.postDelayed({
            EmaAccessibilityService.revealSearchEntry(target.packageName)
            EmaAccessibilityService.searchInPackage(target.packageName, query) { success ->
                if (!success) {
                    done(false, "I opened " + target.label + ", but couldn't reach its search field to type \"$query\".")
                    return@searchInPackage
                }
                main.postDelayed({
                    val ui = EmaAccessibilityService.inspectCurrentUi(12000)
                    val foundQuery = EmaExecutionVerifier.queryVisible(ui, query)
                    done(
                        true,
                        if (foundQuery) "I searched " + target.label + " for " + query + "."
                        else "I typed \"" + query + "\" into " + target.label + "'s search, but could not confirm results loaded."
                    )
                }, 900L)
            }
        }, 800L)
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
            done(true, "Searching the default browser for $query.")
        }.onFailure {
            done(false, "I could not open the default browser.")
        }
    }

    // -------------------------------------------------------------- messages

    /**
     * Send a message through the requested application.
     *
     * The deep link or intent opens the correct conversation with the body
     * already composed wherever Android supports that. EMA then confirms the
     * body is present, presses the submit control through every available route,
     * and verifies the result against observable evidence.
     */
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
            done(false, "Tell me what to say to $recipientName.")
            return
        }

        val label = target.label
        val normalizedLabel = EmaAppResolver.normalize(label)
        val packageName = target.packageName

        val intent = when {
            normalizedLabel.contains("whatsapp") && !phone.isNullOrBlank() -> {
                val digits = phone.filter { it.isDigit() }
                if (digits.isBlank()) null else Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}")
                ).apply {
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            // WhatsApp without a stored number: open the app and find the chat.
            normalizedLabel.contains("whatsapp") -> null
            (normalizedLabel.contains("gmail") || normalizedLabel.contains("mail")) && !email.isNullOrBlank() -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:${Uri.encode(email)}")
                    setPackage(packageName)
                    putExtra(Intent.EXTRA_TEXT, body)
                    putExtra(Intent.EXTRA_SUBJECT, "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            !phone.isNullOrBlank() && (
                normalizedLabel.contains("message") || normalizedLabel.contains("messaging") ||
                    normalizedLabel.contains("sms") || normalizedLabel.contains("messenger")
                ) -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:${Uri.encode(phone)}")
                    setPackage(packageName)
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> null
        }

        if (intent != null && intent.resolveActivity(pm) != null) {
            val started = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (started) {
                // Give the conversation time to open before driving its UI.
                main.postDelayed({
                    completeComposer(
                        packageName = packageName,
                        appLabel = label,
                        recipientName = recipientName,
                        body = body,
                        expectBodyPrefilled = true,
                        done = done
                    )
                }, 1600L)
                return
            }
        }

        composeThroughAccessibleUi(target, recipientName, body, done)
    }

    /**
     * Finish a message that is already open in a composer: make sure the body is
     * present exactly once, press submit, and verify with observable evidence.
     *
     * @param expectBodyPrefilled true when the launching intent already supplied the
     *                            text, so a present body is confirmation rather
     *                            than something to retype.
     */
    private fun completeComposer(
        packageName: String,
        appLabel: String,
        recipientName: String,
        body: String,
        expectBodyPrefilled: Boolean,
        prefillWaits: Int = 0,
        typeAttempts: Int = 0,
        done: (Boolean, String) -> Unit
    ) {
        val screenText = EmaAccessibilityService.currentScreenText(12000)
        val composerText = EmaAccessibilityService.focusedEditableText().trim()

        // Never type a body that is already in the composer or already posted:
        // doing so is how a retry produces a doubled message.
        val bodyPresent = EmaSendControlResolver.transcriptContains(screenText, body) ||
            EmaNodeSnapshot.normalize(composerText) == EmaNodeSnapshot.normalize(body)

        if (!bodyPresent) {
            // Waiting for a deep-linked prefill and retrying a failed type are
            // separate budgets; sharing one counter let the prefill waits consume
            // the typing attempts, so the body was never typed at all.
            if (expectBodyPrefilled && prefillWaits < PREFILL_WAIT_ATTEMPTS) {
                // The composer may still be loading; look again shortly.
                main.postDelayed({
                    completeComposer(
                        packageName, appLabel, recipientName, body,
                        expectBodyPrefilled = true,
                        prefillWaits = prefillWaits + 1,
                        typeAttempts = typeAttempts,
                        done = done
                    )
                }, 700L)
                return
            }
            if (typeAttempts >= MAX_TYPE_ATTEMPTS) {
                // The body could not be confirmed. Submit only when the composer
                // actually holds something, so EMA never presses Send on an empty
                // field; otherwise report the specific failure instead of looping
                // until the command timeout produces a generic one.
                if (composerText.isNotBlank()) {
                    EmaAccessibilityService.pressSend(packageName, 6) { pressed ->
                        if (!pressed) {
                            done(
                                false,
                                "I typed the message to $recipientName in $appLabel, but could not find or activate its Send control."
                            )
                        } else {
                            main.postDelayed({
                                verifySent(packageName, appLabel, recipientName, body, 0, done)
                            }, 900L)
                        }
                    }
                } else {
                    done(
                        false,
                        "I opened the conversation with $recipientName in $appLabel, but its message field would not accept the text."
                    )
                }
                return
            }
            // ACTION_SET_TEXT replaces field content rather than appending, so a
            // retry cannot double the message body.
            val typed = EmaAccessibilityService.setFocusedText(body)
            if (!typed) {
                done(
                    false,
                    "I opened the conversation with $recipientName in $appLabel, but could not type into its message field."
                )
                return
            }
            main.postDelayed({
                completeComposer(
                    packageName, appLabel, recipientName, body,
                    expectBodyPrefilled = false,
                    prefillWaits = prefillWaits,
                    typeAttempts = typeAttempts + 1,
                    done = done
                )
            }, 500L)
            return
        }

        EmaAccessibilityService.pressSend(packageName, 6) { pressed ->
            if (!pressed) {
                done(
                    false,
                    "I typed the message to $recipientName in $appLabel, but could not find or activate its Send control. " +
                        (EmaAccessibilityService.availabilityProblem() ?: "")
                )
                return@pressSend
            }
            main.postDelayed({
                verifySent(packageName, appLabel, recipientName, body, 0, done)
            }, 900L)
        }
    }

    private fun verifySent(
        packageName: String,
        appLabel: String,
        recipientName: String,
        body: String,
        retry: Int = 0,
        done: (Boolean, String) -> Unit
    ) {
        val transcript = EmaAccessibilityService.currentScreenText(14000)
        val composer = EmaAccessibilityService.focusedEditableText().trim()
        val focused = EmaAccessibilityService.composerHasFocus()

        if (EmaSendControlResolver.sendConfirmed(composer, transcript, body, focused)) {
            done(true, "Message sent to $recipientName on $appLabel.")
            return
        }

        if (retry < 2) {
            // The control may have been pressed before the app finished laying
            // out the message. Try the submit route again, then re-verify.
            EmaAccessibilityService.pressSend(packageName, 4) { _ ->
                main.postDelayed({
                    verifySent(packageName, appLabel, recipientName, body, retry + 1, done)
                }, 900L)
            }
            return
        }

        done(
            false,
            "The message to $recipientName is composed in $appLabel, but I could not confirm it was sent. " +
                "Check the chat and press Send if it is still there."
        )
    }

    /**
     * Visible-UI composition: open the app, find the recipient, open the chat,
     * type the body once, then submit and verify.
     */
    private fun composeThroughAccessibleUi(
        target: AppTarget,
        recipientName: String,
        body: String,
        done: (Boolean, String) -> Unit
    ) {
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            val opened = openApp(target.label) != null
            done(
                false,
                if (opened) "I opened ${target.label}, but $unavailable"
                else "I could not open ${target.label}. $unavailable"
            )
            return
        }

        val opened = openApp(target.label)
        if (opened == null) {
            done(false, "I could not open " + target.label + " for " + recipientName + ".")
            return
        }

        main.postDelayed({
            EmaAccessibilityService.searchInPackage(target.packageName, recipientName) { foundRecipient ->
                if (!foundRecipient) {
                    done(
                        false,
                        "I opened ${target.label}, but could not reach its search field to find $recipientName."
                    )
                    return@searchInPackage
                }
                main.postDelayed({
                    val selected = EmaAccessibilityService.tapTextExact(recipientName) ||
                        EmaAccessibilityService.tapFirstResult(recipientName)
                    if (!selected) {
                        done(false, "I searched ${target.label} for $recipientName, but could not open that chat.")
                        return@postDelayed
                    }
                    main.postDelayed({
                        completeComposer(
                            packageName = target.packageName,
                            appLabel = target.label,
                            recipientName = recipientName,
                            body = body,
                            expectBodyPrefilled = false,
                            done = done
                        )
                    }, 1400L)
                }, 1100L)
            }
        }, 1200L)
    }

    /**
     * Open an app, search, wait for its new UI, then activate a named result.
     * Generic: it does not depend on any provider SDK.
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
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            done(false, "I opened ${target.label}, but $unavailable")
            return
        }

        main.postDelayed({
            EmaAccessibilityService.revealSearchEntry(target.packageName)
            EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
                if (!searched) {
                    done(false, "I opened ${target.label}, but couldn't reach its search field.")
                    return@searchInPackage
                }
                main.postDelayed({
                    val tapped = EmaAccessibilityService.tapText(tapText)
                    done(
                        tapped,
                        if (tapped) "I searched ${target.label} and selected $tapText."
                        else "I searched ${target.label}, but couldn't select $tapText from the results."
                    )
                }, 1100L)
            }
        }, 800L)
    }
}
