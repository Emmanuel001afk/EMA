package com.ema.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priority 1 acceptance tests: spoken shortcut definitions (no quote marks),
 * package-name spelling, app vs command resolution, persistence across a
 * simulated process death, and the safety rules.
 */
class EmaShortcutsTest {

    // ------------------------------------------------------ semantic usage

    @Test
    fun semanticParserGroundsATaughtAppTrigger() {
        val env = EmaSemanticParser.Env(
            installedApps = listOf(phormi, spotify),
            shortcuts = mapOf("for me" to EmaShortcuts.Target.App("com.phormi.app", "Phormi"))
        )
        // The single-step form is still handed to the grammar, which resolves
        // the same trigger inside EmaAppActionEngine.findApp.
        assertEquals("for me", (EmaCommandGrammar.interpret("open for me") as EmaCommand.OpenApp).app)
        val result = EmaSemanticParser.understand("open for me and play Burna Boy", env)
        val plan = result as EmaSemanticParser.Result.Plan
        assertEquals("Phormi", (plan.steps[0].command as EmaCommand.OpenApp).app)
    }

    @Test
    fun storedCommandProducesATwoStepPlan() {
        val env = EmaSemanticParser.Env(installedApps = listOf(spotify))
        val result = EmaSemanticParser.understand("open Spotify and play the Offline Backup playlist", env)
        val plan = result as EmaSemanticParser.Result.Plan
        assertEquals(2, plan.steps.size)
        assertTrue(plan.steps[0].command is EmaCommand.OpenApp)
        assertTrue(plan.steps[1].command is EmaCommand.PlayMedia)
    }

    @Test
    fun aTriggerInsideAMessageBodyIsNeverSubstituted() {
        val env = EmaSemanticParser.Env(
            installedApps = listOf(EmaAppResolver.App("WhatsApp", "com.whatsapp"), phormi),
            shortcuts = mapOf("for me" to EmaShortcuts.Target.App("com.phormi.app", "Phormi"))
        )
        val result = EmaSemanticParser.understand("send John a message saying get bread for me", env)
        val message = ((result as EmaSemanticParser.Result.Plan).steps.single().command as EmaCommand.SendMessage)
        assertEquals("John", message.recipient)
        assertEquals("get bread for me", message.body)
    }


    private val phormi = EmaAppResolver.App("Phormi", "com.phormi.app")
    private val phormiBrowser = EmaAppResolver.App("Phormi Browser", "com.phormi.browser")
    private val spotify = EmaAppResolver.App("Spotify", "com.spotify.music")

    private fun remember(text: String, apps: List<EmaAppResolver.App> = listOf(phormi, spotify)): EmaMemory.Applied {
        val op = EmaMemory.parse(text)
        assertTrue("parse failed for: $text", op != null)
        return EmaMemory().apply(op!!, apps)!!
    }

    // ------------------------------------------------------------- parsing

    @Test
    fun parsesSpokenDefinitionWithoutQuoteMarks() {
        val definition = EmaShortcuts.parseDefinition(
            "Remember that whenever I say for me or Phormi browser it means open the Phormi app, package spelling P H O R M I"
        )!!
        assertEquals(listOf("for me", "Phormi browser"), definition.triggers)
        assertEquals("phormi", definition.packageSpelling)
        assertEquals("open the Phormi app", definition.meaning)
    }

    @Test
    fun parsesDefinitionWithOpenVerbOnly() {
        val definition = EmaShortcuts.parseDefinition("Remember that when I say offline backup, open Spotify")!!
        assertEquals(listOf("offline backup"), definition.triggers)
        assertEquals("open Spotify", definition.meaning)
        assertNull(definition.packageSpelling)
    }

    @Test
    fun parsesDefinitionForAWholeCommand() {
        val definition = EmaShortcuts.parseDefinition(
            "Remember that offline backup means open Spotify and play the Offline Backup playlist"
        )!!
        val applied = EmaMemory().apply(EmaMemory.Op.Shortcut(definition), listOf(spotify))!!
        val target = applied.memory.shortcuts["offline backup"] as EmaShortcuts.Target.Command
        assertEquals("open Spotify and play the Offline Backup playlist", target.text)
        assertTrue(applied.reply.contains("'offline backup'"))
    }

    @Test
    fun spelledPackageJoinsLettersAndLiteralPackages() {
        assertEquals("phormi", EmaShortcuts.spelledPackage("P H O R M I"))
        assertEquals("phormi", EmaShortcuts.spelledPackage("P.H.O.R.M.I"))
        assertEquals("com.phormi.app", EmaShortcuts.spelledPackage("com.phormi.app"))
        assertNull(EmaShortcuts.spelledPackage("Phormi"))
    }

    // ------------------------------------------------------------ resolution

    @Test
    fun savesAppShortcutForEveryTriggerUsingSpelledPackage() {
        val applied = remember(
            "Remember that whenever I say for me or Phormi browser it means open the Phormi app, package spelling P H O R M I"
        )
        val first = applied.memory.shortcuts["for me"] as EmaShortcuts.Target.App
        val second = applied.memory.shortcuts["phormi browser"] as EmaShortcuts.Target.App
        assertEquals("com.phormi.app", first.packageName)
        assertEquals("com.phormi.app", second.packageName)
        assertEquals("Phormi", second.label)
        assertTrue(applied.reply.contains("'for me'"))
        assertTrue(applied.reply.contains("'Phormi browser'") || applied.reply.contains("'phormi browser'"))
        assertTrue(applied.reply.contains("open Phormi"))
    }

    @Test
    fun ambiguousPackageSpellingAsksInsteadOfGuessing() {
        val applied = remember(
            "Remember that whenever I say phormi it means open Phormi, package spelling P H O R M I",
            listOf(phormi, EmaAppResolver.App("Phormi", "com.phormi.work"))
        )
        assertEquals(emptyMap<String, EmaShortcuts.Target>(), applied.memory.shortcuts)
        assertEquals(2, applied.candidateApps.size)
        assertTrue(applied.pendingShortcut != null)
        assertTrue(applied.reply.contains("Which one"))
    }

    @Test
    fun aMoreSpecificPackageSpellingIsNotAmbiguous() {
        // "Phormi Browser" contains the same prefix, but the exact package leaf
        // and label "Phormi" are the strongest evidence and must win.
        val applied = remember(
            "Remember that whenever I say for me or Phormi browser it means open the Phormi app, package spelling P H O R M I",
            listOf(phormi, phormiBrowser)
        )
        assertEquals("com.phormi.app", (applied.memory.shortcuts["for me"] as EmaShortcuts.Target.App).packageName)
        assertEquals("com.phormi.app", (applied.memory.shortcuts["phormi browser"] as EmaShortcuts.Target.App).packageName)
    }

    @Test
    fun taughtAppCategoryAlsoBecomesItsOwnerPreferredGenericTarget() {
        val applied = remember("remember that when I say for me browser it means open Phormi")
        assertEquals("com.phormi.app", (applied.memory.shortcuts["for me browser"] as EmaShortcuts.Target.App).packageName)
        assertEquals("Phormi", applied.memory.defaults["browser"])
    }

    @Test
    fun forgettingAnAppCategoryShortcutClearsItsDerivedDefault() {
        val saved = remember("remember that when I say for me browser it means open Phormi").memory
        val forgotten = saved.apply(EmaMemory.Op.Forget("for me browser"))!!.memory
        assertEquals(emptyMap<String, String>(), forgotten.defaults)
    }

    @Test
    fun appShortcutIsFoundByTriggerAndWithoutLeadingMy() {
        val memory = remember("remember that my portal means open the Phormi app").memory
        assertEquals("com.phormi.app", memory.appShortcut("my portal")?.packageName)
        assertEquals("com.phormi.app", memory.appShortcut("portal")?.packageName)
        assertNull(memory.appShortcut("send a message saying hi"))
    }

    @Test
    fun aliasesStillWorkForPeopleAndAreNotShortcuts() {
        val applied = remember("when I say my wife I mean Ada Obi")
        assertEquals("Ada Obi", applied.memory.aliases["wife"])
        assertTrue(applied.memory.shortcuts.isEmpty())
    }

    @Test
    fun shortcutIsBoundedAndForgettable() {
        var memory = EmaMemory()
        for (i in 1..EmaMemory.MAX_SHORTCUTS + 2) {
            val definition = EmaShortcuts.Definition(listOf("trigger$i"), "open Phormi")
            memory = memory.apply(EmaMemory.Op.Shortcut(definition), listOf(phormi))!!.memory
        }
        assertEquals(EmaMemory.MAX_SHORTCUTS, memory.shortcuts.size)
        val forgotten = memory.apply(EmaMemory.Op.Forget("trigger1"))!!.memory
        assertNull(forgotten.shortcuts["trigger1"])
    }

    @Test
    fun secretsAreStillRefused() {
        val applied = remember("remember that my bank password means hunter2", listOf(phormi))
        // The word "means" would otherwise make this a definition; the secret
        // rule must win and nothing may be stored.
        assertTrue(applied.memory.shortcuts.isEmpty())
        assertTrue(applied.reply.contains("don't store") || applied.reply.contains("couldn't match"))
    }

    // ----------------------------------------------------------- persistence

    @Test
    fun shortcutsSurviveRecreatingTheStore() {
        var disk: String? = null
        val first = EmaMemoryPersistence({ disk }, { disk = it; true })
        val memory = remember(
            "remember that whenever I say for me it means open the Phormi app, package spelling P H O R M I"
        ).memory
        assertTrue(first.save(memory))
        // A new process has no in-memory cache; it reads the same storage.
        val second = EmaMemoryPersistence({ disk }, { disk = it; true })
        val loaded = second.load()
        val target = loaded.shortcuts["for me"] as EmaShortcuts.Target.App
        assertEquals("com.phormi.app", target.packageName)
        assertEquals("Phormi", target.label)
        assertEquals(memory, loaded)
    }

    @Test
    fun encodingCarriesSchemaVersionAndRoundTripsBothKinds() {
        val memory = EmaMemory(
            facts = mapOf("doctor" to "Dr Okafor"),
            shortcuts = linkedMapOf(
                "for me" to EmaShortcuts.Target.App("com.phormi.app", "Phormi"),
                "offline backup" to EmaShortcuts.Target.Command("open Spotify and play the Offline Backup playlist")
            )
        )
        val encoded = EmaMemory.encode(memory)
        assertTrue(encoded.startsWith("V\t${EmaMemory.SCHEMA_VERSION}"))
        assertEquals(memory, EmaMemory.decode(encoded))
    }

    @Test
    fun oldEncodingWithoutShortcutsStillLoads() {
        val legacy = "F\tdoctor\tDr Okafor\nA\twife\tAda Obi\n"
        assertEquals(EmaMemory(facts = mapOf("doctor" to "Dr Okafor"), aliases = mapOf("wife" to "Ada Obi")), EmaMemory.decode(legacy))
    }
}

/** Speech and registration acceptance, kept beside the shortcut behaviour. */
class EmaVoiceHintsTest {

    @Test fun prefersHypothesisContainingALearnedTrigger() {
        val chosen = EmaSpeechRecognition.chooseHypothesis(
            listOf("open for me later", "open the browser for me", "for me"),
            listOf("for me", "Phormi", "Spotify")
        )
        assertEquals("for me", chosen)
    }

    @Test fun prefersInstalledAppLabelWhenNoTriggerMatches() {
        val chosen = EmaSpeechRecognition.chooseHypothesis(
            listOf("open spot a fi", "open Spotify", "open the spy"),
            listOf("Spotify")
        )
        assertEquals("open Spotify", chosen)
    }

    @Test fun keepsFirstHypothesisWithoutVocabulary() {
        val chosen = EmaSpeechRecognition.chooseHypothesis(listOf("first choice", "second"), emptyList())
        assertEquals("first choice", chosen)
    }

    @Test fun nullOrBlankHypothesesAreRejected() {
        assertNull(EmaSpeechRecognition.chooseHypothesis(null, listOf("x")))
        assertNull(EmaSpeechRecognition.chooseHypothesis(listOf(" ", ""), listOf("x")))
    }

    @Test fun registrationStatusUsesRealState() {
        assertTrue(EmaAssistantRegistration.describe(EmaAssistantRegistration.Status(true, true, true)).contains("active"))
        assertTrue(EmaAssistantRegistration.describe(EmaAssistantRegistration.Status(false, true, true)).contains("not enabled"))
        assertTrue(EmaAssistantRegistration.describe(EmaAssistantRegistration.Status(true, false, true)).contains("not active"))
        assertTrue(EmaAssistantRegistration.describe(EmaAssistantRegistration.Status(true, true, false)).contains("recognition service"))
    }
}
