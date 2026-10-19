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

    /** Condition waits, never fixed sleeps. Shared by every media/app path. */
    private val waiter by lazy {
        EmaConditionWaiter(
            EmaPlanExecutor.Scheduler { delay, block ->
                val runnable = Runnable { block() }
                main.postDelayed(runnable, delay)
                EmaPlanExecutor.Cancellable { main.removeCallbacks(runnable) }
            },
            { android.os.SystemClock.elapsedRealtime() }
        )
    }

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

    /** Installed launcher applications with their real package names. */
    fun installedApps(): List<EmaAppResolver.App> {
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

        // A user-taught trigger is an exact identity, so it is consulted first.
        // "for me" must open the app the user mapped to it, without replacing
        // the same words inside a message body or a query (only name slots
        // reach this function).
        EmaMemoryStore.load(context).appShortcut(wanted)?.let { shortcut ->
            val installed = installedApps().firstOrNull { it.packageName == shortcut.packageName }
                ?: return null // Uninstalled: never silently open a different app.
            return AppTarget(installed.label, installed.packageName)
        }

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

    /** Resolve an exact package name (used by stored app shortcuts). */
    fun findAppByPackage(packageName: String): AppTarget? {
        val app = installedApps().firstOrNull { it.packageName == packageName } ?: return null
        return AppTarget(app.label, app.packageName)
    }

    /**
     * Launch an installed application by its exact package name.
     *
     * Stored shortcuts identify an app by package, not by label: two apps can
     * share a label ("Browser"), and re-resolving a label can open the wrong
     * one. The package is the identity the user taught EMA.
     */
    fun openPackage(packageName: String): AppTarget? {
        val installed = installedApps().firstOrNull { it.packageName == packageName } ?: return null
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            AppTarget(installed.label, installed.packageName)
        }.getOrElse {
            android.util.Log.e("EMA", "Could not launch " + installed.label, it)
            null
        }
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

    /**
     * Wait for a launched package to reach the foreground.
     *
     * "startActivity returned" is not evidence: the next plan step used to run
     * while the app was still on its cold-start splash. But the opposite
     * mistake is just as bad: requiring the screen to stop changing made
     * working tasks fail on phones whose screen never settles (a chat list, a
     * feed, a progress bar). Arrival in the foreground is what counts; the
     * screen settling is best effort.
     *
     * The result is therefore a three-way answer, not a boolean:
     *  - [EmaAppArrival.ARRIVED_SETTLED] the app is in front and calm,
     *  - [EmaAppArrival.ARRIVED_BUSY] the app is in front but still moving, or
     *    EMA cannot watch the screen because screen control is off,
     *  - [EmaAppArrival.NOT_ARRIVED] the app never came to the foreground.
     */
    fun awaitForeground(target: AppTarget, done: (EmaAppArrival) -> Unit) {
        EmaForegroundWait.await(
            waiter = waiter,
            targetPackage = target.packageName,
            foregroundPackage = {
                if (EmaAccessibilityService.isConnected()) EmaAccessibilityService.foregroundPackage() else null
            },
            screenText = { EmaAccessibilityService.currentScreenText(1200) },
            done = done
        )
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
                    // A play-from-search intent is asynchronous: the app may take
                    // seconds to resolve the query. Poll the media session for up
                    // to 4 s before deciding that it did not work and falling
                    // back to the visible path. Never assume success from the fact
                    // that the intent was accepted.
                    waiter.until(4_000, pollMs = 300, probe = { playbackConfirmed(target, query) }) { playing ->
                        if (playing) done(true, "Playing $query in $appName.")
                        else if (unavailable != null) {
                            // The app was opened and asked to play. EMA cannot
                            // watch the screen or the media session, so it says
                            // exactly that instead of calling the whole thing a
                            // failure.
                            done(
                                false,
                                "I opened $appName and asked it to play $query, but I could not confirm it started because EMA screen control is off."
                            )
                        } else searchAndPlay(target, appName, query, unavailable, done)
                    }
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
                if (opened) "I opened $appName, but $unavailable, so I did not start \"$query\"."
                else "I couldn't open $appName. $unavailable"
            )
            return
        }

        val opened = openApp(target.label)
        if (opened == null) {
            done(false, "I couldn't open $appName.")
            return
        }

        // Wait for the app to be in front of the user before touching its UI;
        // fixed sleeps raced Spotify's cold start and typed into the launcher.
        // A screen that never settles is fine: the search itself is the proof.
        awaitForeground(target) { arrival ->
            if (arrival == EmaAppArrival.NOT_ARRIVED) {
                done(false, "I opened $appName, but it did not come to the foreground in time.")
                return@awaitForeground
            }
            // Search often lives behind its own navigation entry; reveal it
            // before waiting for an editable field that is not on this screen.
            EmaAccessibilityService.revealSearchEntry(target.packageName)
            EmaAccessibilityService.searchInPackage(target.packageName, query) { searched ->
                if (!searched) {
                    done(false, "I opened $appName, but could not reach its search field to type \"$query\".")
                    return@searchInPackage
                }
                // Wait for a non-empty, settled result list before choosing.
                waiter.until(
                    timeoutMs = 8_000,
                    pollMs = 250,
                    probe = { EmaAccessibilityService.resultItems(target.packageName, query).isNotEmpty() }
                ) {
                    selectWithRetry(target, appName, query, 3, done)
                }
            }
        }
    }

    /**
     * Choose the requested result, retrying a bounded three times.
     *
     * Result lists settle late on cold start; one immediate attempt used to
     * report "could not select" while the rows were still rendering. Each
     * retry re-checks that the foreground app is still the intended one.
     */
    private fun selectWithRetry(
        target: AppTarget,
        appName: String,
        query: String,
        attemptsLeft: Int,
        done: (Boolean, String) -> Unit
    ) {
        if (EmaAccessibilityService.foregroundPackage() != target.packageName) {
            done(false, "The active app changed before I could select $query. I stopped.")
            return
        }
        val selected = EmaAccessibilityService.selectMediaResult(target.packageName, query)
        when {
            selected -> waiter.delay(900) { startPlayback(target, appName, query, done) }
            attemptsLeft > 1 -> waiter.delay(700) { selectWithRetry(target, appName, query, attemptsLeft - 1, done) }
            else -> done(false, "I searched $appName, but could not verify and select a result matching $query.")
        }
    }

    private fun startPlayback(target: AppTarget, appName: String, query: String, done: (Boolean, String) -> Unit) {
        if (EmaAccessibilityService.foregroundPackage() != target.packageName) {
            done(false, "The active app changed. I did not press Play in another app.")
            return
        }
        // Known-playing: never press Play, which would pause it.
        val state = EmaNotificationListenerService.mediaPlaying(context, target.packageName)
        if (state == true) {
            waiter.until(4_000, pollMs = 250, probe = { playbackConfirmed(target, query) }) { confirmed ->
                done(
                    confirmed,
                    if (confirmed) "Playing $query in $appName."
                    else "Something is already playing in $appName; I did not press Play because that would pause it."
                )
            }
            return
        }
        // Known not playing (or unobservable): at most one Play press. The
        // visible control EMA matches is a Play control, not a Pause control,
        // and EMA never presses a second time.
        val activated = EmaAccessibilityService.playInTarget(target.packageName)
        if (!activated) {
            done(false, "I selected \"$query\" in $appName, but could not find a Play control to start it.")
            return
        }
        waiter.until(4_000, pollMs = 250, probe = { playbackConfirmed(target, query) }) { verified ->
            done(
                verified,
                when {
                    verified -> "Playing $query in $appName."
                    state == null -> "I selected \"$query\" in $appName and pressed Play once, but I could not verify playback. Turning on notification access for EMA lets me check the media session."
                    else -> "I selected \"$query\" in $appName and pressed Play, but the media session never reported the requested title playing."
                }
            )
        }
    }

    /** The requested app's active session must report the requested title.
     * Global audio could belong to another song or another app.
     */
    private fun playbackConfirmed(target: AppTarget, query: String): Boolean =
        EmaNotificationListenerService.requestedMediaPlaying(context, target.packageName, query)

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

        // Patience: wait for the app to actually be in front of the user before
        // touching its UI. A fixed 800 ms was a guess and broke cold starts.
        // A screen that keeps moving is not a failure: the search is the proof.
        awaitForeground(target) { arrival ->
            if (arrival == EmaAppArrival.NOT_ARRIVED) {
                done(false, "I opened " + target.label + ", but it did not come to the foreground in time.")
                return@awaitForeground
            }
            EmaAccessibilityService.revealSearchEntry(target.packageName)
            EmaAccessibilityService.searchInPackage(target.packageName, query) { success ->
                if (!success) {
                    done(false, "I opened " + target.label + ", but couldn't reach its search field to type \"$query\".")
                    return@searchInPackage
                }
                waiter.until(8_000, pollMs = 250, probe = {
                    EmaExecutionVerifier.queryVisible(EmaAccessibilityService.inspectCurrentUi(12000), query)
                }) {
                    done(
                        true,
                        if (it) "I searched " + target.label + " for " + query + "."
                        else "I typed \"" + query + "\" into " + target.label + "'s search, but could not confirm results loaded."
                    )
                }
            }
        }
    }

    /**
     * Search inside an app and observe whether a result list really appeared.
     * Success needs result rows on screen; the query merely echoed in the
     * search box is reported as unverified.
     */
    fun searchVerified(appName: String, query: String, done: (EmaStepOutcome, AppTarget?) -> Unit) {
        val target = findApp(appName)
        if (target == null) {
            done(EmaStepOutcome(EmaOutcomeStatus.FAILED, "I couldn't find $appName on this phone."), null)
            return
        }
        if (query.isBlank()) {
            val opened = openApp(target.label)
            if (opened == null) { done(EmaStepOutcome(EmaOutcomeStatus.FAILED, "I couldn't open ${target.label}."), null); return }
            main.postDelayed({
                val revealed = EmaAccessibilityService.revealSearchEntry(target.packageName)
                done(EmaStepOutcome(EmaOutcomeStatus.SUCCESS,
                    if (revealed) "Opened ${target.label} and its search. What should I search for?" else "Opened ${target.label}."), target)
            }, 900L)
            return
        }
        openAndSearch(target.label, query) { ok, message ->
            if (!ok) {
                done(EmaOutcomeClassifier.classify(message, false).copy(retryable = !message.contains("couldn't find", true)), target)
                return@openAndSearch
            }
            // Observe: wait (bounded) for result rows to appear.
            var polls = 0
            fun check() {
                val rows = EmaAccessibilityService.resultItems(target.packageName, query).filterNot { it.sponsored }
                if (rows.isNotEmpty()) {
                    done(EmaStepOutcome(EmaOutcomeStatus.SUCCESS, "I searched ${target.label} for $query."), target)
                } else if (polls++ < 6) {
                    main.postDelayed({ check() }, 500L)
                } else {
                    done(EmaStepOutcome(EmaOutcomeStatus.UNVERIFIED,
                        "I typed \"$query\" into ${target.label}'s search, but I can't see a results list yet."), target)
                }
            }
            check()
        }
    }

    /**
     * Choose a result in [appName] (or the foreground app when null) and
     * verify it opened or started playing. See [EmaResultSelectionFlow].
     */
    fun selectResult(
        appName: String?,
        selection: EmaSelection,
        play: Boolean,
        alreadyChosen: List<String>,
        query: String?,
        done: (EmaStepOutcome, String?, AppTarget?) -> Unit
    ) {
        val unavailable = EmaAccessibilityService.availabilityProblem()
        if (unavailable != null) {
            done(EmaStepOutcome(EmaOutcomeStatus.NEEDS_PERMISSION, "To choose a result on screen I need screen control. $unavailable"), null, null)
            return
        }
        val foreground = EmaAccessibilityService.foregroundPackage()
        val target = if (appName.isNullOrBlank()) {
            installedApps().firstOrNull { it.packageName == foreground }?.let { AppTarget(it.label, it.packageName) }
                ?: if (foreground.isNotBlank()) AppTarget(foreground.substringAfterLast('.'), foreground) else null
        } else findApp(appName)
        if (target == null) {
            done(EmaStepOutcome(EmaOutcomeStatus.FAILED,
                if (appName.isNullOrBlank()) "I can't tell which app's results you mean." else "I couldn't find $appName on this phone."), null, null)
            return
        }
        val port = object : EmaResultSelectionFlow.Port {
            override fun foregroundPackage() = EmaAccessibilityService.foregroundPackage()
            override fun results(packageName: String, query: String?) = EmaAccessibilityService.resultItems(packageName, query)
            override fun click(packageName: String, item: EmaResultItem, query: String?) = EmaAccessibilityService.clickResult(packageName, item, query)
            override fun scrollForward(packageName: String) = EmaAccessibilityService.scrollResultsForward(packageName)
            override fun back() = EmaAccessibilityService.pressBack()
            override fun media(packageName: String): EmaResultSelectionFlow.Media? =
                EmaNotificationListenerService.activeMedia(context, packageName)?.let { EmaResultSelectionFlow.Media(it.title, it.playing) }
                    ?: if (EmaNotificationListenerService.isConnected()) EmaResultSelectionFlow.Media("", false) else null
            override fun schedule(delayMs: Long, block: () -> Unit) { main.postDelayed(block, delayMs) }
            override val rerunSearch: ((done: (Boolean) -> Unit) -> Unit)? =
                if (query.isNullOrBlank()) null else { cb -> openAndSearch(target.label, query) { ok, _ -> cb(ok) } }
        }
        EmaResultSelectionFlow(port, target.packageName, target.label, selection, play, alreadyChosen, query).run { result ->
            done(result.outcome, result.chosenTitle, target)
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
                val rawDigits = phone.filter { it.isDigit() }
                val digits = if (rawDigits.startsWith("00")) rawDigits.drop(2) else rawDigits
                // wa.me requires an international number. Do not guess a country
                // for local contacts (e.g. Nigerian 080…); use visible chat search.
                if (digits.length !in 8..15 || digits.startsWith("0")) null else Intent(
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
        if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            EmaAccessibilityService.targetSnapshot(packageName).packageName != packageName) {
            done(false, "The phone locked or active screen changed. I did not type or send the message to $recipientName.")
            return
        }
        val chat = EmaAccessibilityService.targetSnapshot(packageName)
        if (!EmaActionEvidence.recipientHeaderMatches(chat, packageName, recipientName)) {
            done(false, "I could not verify $recipientName in the chat header in $appLabel. I did not type or send the message.")
            return
        }
        val composerText = (chat.focusedNode ?: chat.firstEditable)?.text.orEmpty().trim()

        // Old transcript text is not evidence that this request is composed.
        val bodyPresent = composerText == body.trim()

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
                // Never submit arbitrary existing text when exact read-back fails.
                done(
                    false,
                    "I opened the conversation with $recipientName in $appLabel, but could not confirm the exact message in its composer. I did not press Send."
                )
                return
            }
            // ACTION_SET_TEXT replaces field content rather than appending, so a
            // retry cannot double the message body.
            val typed = EmaAccessibilityService.typeMessageVerified(packageName, recipientName, body)
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

        val transcriptCountBefore = EmaActionEvidence.transcriptBodyCount(EmaAccessibilityService.targetSnapshot(packageName), body)
        val pressed = EmaAccessibilityService.sendVerified(packageName, recipientName, body)
        run {
            if (!pressed) {
                done(
                    false,
                    "I typed the message to $recipientName in $appLabel, but could not find or activate its Send control. " +
                        (EmaAccessibilityService.availabilityProblem() ?: "")
                )
                return@run
            }
            main.postDelayed({
                verifySent(packageName, appLabel, recipientName, body, transcriptCountBefore, 0, done)
            }, 900L)
        }
    }

    private fun verifySent(
        packageName: String,
        appLabel: String,
        recipientName: String,
        body: String,
        transcriptCountBefore: Int,
        retry: Int = 0,
        done: (Boolean, String) -> Unit
    ) {
        if (EmaAccessibilityService.targetSnapshot(packageName).packageName != packageName) {
            done(false, "The active screen changed before I could verify the message. Check $appLabel before trying again.")
            return
        }
        val screen = EmaAccessibilityService.targetSnapshot(packageName)
        val transcript = screen.nodes.filter { !it.editable }.joinToString("\n") { it.label }
        val composer = (screen.focusedNode ?: screen.firstEditable)?.text.orEmpty().trim()
        val focused = screen.focusedNode != null
        val newBubble = EmaActionEvidence.transcriptBodyCount(screen, body) > transcriptCountBefore
        if (newBubble && EmaActionEvidence.recipientHeaderMatches(screen, packageName, recipientName) &&
            EmaSendControlResolver.sendConfirmed(composer, transcript, body, focused)) {
            done(true, "Message submitted to $recipientName on $appLabel; a new chat bubble is visible. Delivery is not confirmed.")
            return
        }

        if (retry < 2) {
            // Observe only. Re-pressing Send after an uncertain result can
            // duplicate a real message; a retry must never repeat the action.
            main.postDelayed({
                verifySent(packageName, appLabel, recipientName, body, transcriptCountBefore, retry + 1, done)
            }, 900L)
            return
        }

        done(
            false,
            "I pressed Send in $appLabel, but could not confirm a new message to $recipientName. " +
                "Check the chat before trying again to avoid a duplicate."
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
                    val selected = EmaAccessibilityService.selectContactInTarget(target.packageName, recipientName)
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
