package com.ema.assistant

import java.util.Locale

/** Small, explicit, offline syllogism engine. Premises are hypothetical data, never commands. */
object EmaSentenceLogic {
    private const val MAX_LENGTH = 3_000
    private const val MAX_PREMISES = 24
    private const val WORD = "([\\p{L}][\\p{L}'’\\-]{0,39})"
    private val subclass = Regex("(?i)^all\\s+$WORD\\s+are\\s+$WORD$")
    private val disjoint = Regex("(?i)^no\\s+$WORD\\s+are\\s+$WORD$")
    private val fact = Regex("(?i)^$WORD\\s+is\\s+(not\\s+)?(?:a|an)\\s+$WORD$")
    private val question = Regex("(?i)^is\\s+$WORD\\s+(not\\s+)?(?:a|an)\\s+$WORD\\??$")
    private val reserved = setOf("all", "no", "not", "none", "some", "every", "any", "it", "he", "she", "they", "you", "we", "i", "this", "that", "these", "those")

    data class Rule(val from: String, val to: String, val text: String)
    data class Fact(val subject: String, val category: String, val negative: Boolean, val text: String)
    data class Problem(val rules: List<Rule>, val exclusions: List<Rule>, val facts: List<Fact>, val subject: String, val category: String, val negativeQuestion: Boolean)
    sealed class Parsed {
        data class Valid(val problem: Problem) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }
    enum class Verdict { YES, NO, UNKNOWN, CONFLICT }
    data class Answer(val verdict: Verdict, val positiveEvidence: List<String>, val negativeEvidence: List<String>)

    private fun identifier(raw: String): String? = raw.lowercase(Locale.ROOT).takeIf { it !in reserved }
    /** A small regular-English plural rule; no claim of general lemmatization. */
    private fun category(raw: String): String? {
        val s = identifier(raw) ?: return null
        return when {
            s.length > 5 && (s.endsWith("sses") || s.endsWith("ches") || s.endsWith("shes") || s.endsWith("xes")) -> s.dropLast(2)
            s.length > 3 && s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is") &&
                s !in setOf("series", "species", "news") -> s.dropLast(1)
            else -> s
        }
    }

    fun parse(raw: String): Parsed? {
        if (!raw.trimStart().startsWith("reason:", ignoreCase = true)) return null
        if (raw.length > MAX_LENGTH) return Parsed.Invalid("That reasoning problem is too long. Use at most $MAX_LENGTH characters.")
        val body = raw.trim().substringAfter(':').trim()
        val pieces = body.split(';').map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }
        if (pieces.size < 2 || pieces.size > MAX_PREMISES + 1) return Parsed.Invalid(
            "Give one to $MAX_PREMISES premises and a final question, separated by semicolons.")
        val q = question.matchEntire(pieces.last()) ?: return Parsed.Invalid(
            "End with a question like: is Luna an animal? This mode accepts single-word names and categories.")
        val subject = identifier(q.groupValues[1]) ?: return Parsed.Invalid("Use an explicit name, not a pronoun, in the question.")
        val target = category(q.groupValues[3]) ?: return Parsed.Invalid("Use an explicit single-word category in the question.")
        val rules = mutableListOf<Rule>()
        val exclusions = mutableListOf<Rule>()
        val facts = mutableListOf<Fact>()
        for (part in pieces.dropLast(1)) {
            val all = subclass.matchEntire(part)
            val no = disjoint.matchEntire(part)
            val member = fact.matchEntire(part)
            when {
                all != null -> {
                    val left = category(all.groupValues[1]) ?: return Parsed.Invalid("I couldn't interpret the category in: $part")
                    val right = category(all.groupValues[2]) ?: return Parsed.Invalid("I couldn't interpret the category in: $part")
                    rules += Rule(left, right, part)
                }
                no != null -> {
                    val left = category(no.groupValues[1]) ?: return Parsed.Invalid("I couldn't interpret the category in: $part")
                    val right = category(no.groupValues[2]) ?: return Parsed.Invalid("I couldn't interpret the category in: $part")
                    exclusions += Rule(left, right, part)
                }
                member != null -> {
                    val name = identifier(member.groupValues[1]) ?: return Parsed.Invalid("Use explicit names, not pronouns, in premises.")
                    val type = category(member.groupValues[3]) ?: return Parsed.Invalid("I couldn't interpret the category in: $part")
                    facts += Fact(name, type, member.groupValues[2].isNotBlank(), part)
                }
                else -> return Parsed.Invalid("I can't safely interpret this premise: $part. Use all X are Y, no X are Y, or a named individual is a Y.")
            }
        }
        return Parsed.Valid(Problem(rules, exclusions, facts, subject, target, q.groupValues[2].isNotBlank()))
    }

    /** Returns one bounded proof per reachable class, without treating cycles as new facts. */
    private fun closure(start: String, rules: List<Rule>): Map<String, List<String>> {
        val paths = linkedMapOf(start to emptyList<String>())
        val queue = ArrayDeque<String>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (r in rules.filter { it.from == node }) if (r.to !in paths) {
                paths[r.to] = paths.getValue(node) + r.text
                queue.add(r.to)
            }
        }
        return paths
    }

    fun solve(p: Problem): Answer {
        val positive = linkedMapOf<String, List<String>>()
        for (f in p.facts.filter { it.subject == p.subject && !it.negative }) {
            for ((type, path) in closure(f.category, p.rules)) positive.putIfAbsent(type, listOf(f.text) + path)
        }
        val positiveProof = positive[p.category]
        // If the target is a subclass of an excluded/negated class, it is also excluded.
        val targetAncestors = closure(p.category, p.rules)
        var negativeProof: List<String>? = null
        for (f in p.facts.filter { it.subject == p.subject && it.negative }) {
            val path = targetAncestors[f.category] ?: continue
            negativeProof = path + f.text
            break
        }
        if (negativeProof == null) {
            loop@ for (e in p.exclusions) {
                for ((side, other) in listOf(e.from to e.to, e.to to e.from)) {
                    val memberProof = positive[side] ?: continue
                    val targetPath = targetAncestors[other] ?: continue
                    negativeProof = memberProof + e.text + targetPath
                    break@loop
                }
            }
        }
        val yes = if (p.negativeQuestion) negativeProof else positiveProof
        val no = if (p.negativeQuestion) positiveProof else negativeProof
        val verdict = when {
            yes != null && no != null -> Verdict.CONFLICT
            yes != null -> Verdict.YES
            no != null -> Verdict.NO
            else -> Verdict.UNKNOWN
        }
        return Answer(verdict, yes.orEmpty(), no.orEmpty())
    }

    fun respond(parsed: Parsed): String = when (parsed) {
        is Parsed.Invalid -> parsed.reason + " I haven't taken any action."
        is Parsed.Valid -> {
            val result = solve(parsed.problem)
            when (result.verdict) {
                Verdict.YES -> "From your premises, yes. Evidence: ${result.positiveEvidence.joinToString(" → ")}."
                Verdict.NO -> "From your premises, no. Evidence: ${result.negativeEvidence.joinToString(" → ")}."
                Verdict.CONFLICT -> "Your premises support both the statement and its negation; they conflict for this question. " +
                    "Evidence for yes: ${result.positiveEvidence.joinToString(" → ")}. Evidence for no: ${result.negativeEvidence.joinToString(" → ")}."
                Verdict.UNKNOWN -> "The supplied premises don't establish either answer. I can't conclude yes or no."
            }
        }
    }
}
