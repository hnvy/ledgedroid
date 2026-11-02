package app.ledgedroid.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One of quick add's prompts, in order: date, payee, then account and amount for each posting, then confirm. */
sealed interface QuickStep {
    data object Date : QuickStep
    data object Payee : QuickStep
    data class Account(val index: Int) : QuickStep
    data class Amount(val index: Int) : QuickStep
    data object Confirm : QuickStep
}

/** A suggestion at a prompt. [value] goes in the field; choosing a [template] fills in the rest. */
data class QuickCompletion(val value: String, val template: Template? = null)

/** What a prompt asks, what Enter on an empty answer gives ([default]), and a hint about what else works. */
data class QuickPrompt(val label: String, val default: String?, val hint: String)

/**
 * Where quick add is: the transaction so far, the prompt, the [template] chosen (its values are the
 * prompts' defaults), the answers given so far ([log], to show above the prompt), and the state before the
 * last answer, to go back to.
 */
data class QuickAddState(
    val draft: TransactionDraft,
    val step: QuickStep = QuickStep.Date,
    val template: Template? = null,
    val log: List<Pair<String, String>> = emptyList(),
    val previous: QuickAddState? = null,
) {
    /** The postings answered so far. */
    val entered: Int
        get() = when (val s = step) {
            is QuickStep.Account -> s.index
            is QuickStep.Amount -> s.index + 1
            QuickStep.Confirm -> draft.postings.size
            else -> 0
        }
}

sealed interface QuickResult {
    data class Next(val state: QuickAddState) : QuickResult
    data class Problem(val message: String) : QuickResult

    /** The transaction is confirmed: add it. */
    data class Done(val draft: TransactionDraft) : QuickResult
}

/**
 * Adding a transaction one prompt at a time, like hledger add: Date, Payee (or a template), then Account
 * and Amount for each posting, then a last look before adding it.
 *
 *  - Enter on an empty answer takes the prompt's default: today's date, a template's values, and for
 *    the second and later amounts whatever balances the transaction, as hledger add does.
 *  - Dates: 2026-11-02, 2026/11/02, 11-02 (this year), 2 (this month), today, yesterday, tomorrow, or a
 *    number of days back or ahead like -3 or +1.
 *  - Payee: "payee | note" adds a note. Choosing a template (among the suggestions) fills in the rest,
 *    which then comes up as each prompt's default.
 *  - Amounts: 4.50, $4.50, 4.50 EUR, -$4.50, or a sum like 12.50+3.20. Without a currency, the posting
 *    gets the template's, the one before's, or the journal's usual one.
 *  - Account: an empty answer (with no default) after two postings, or ".", finishes the postings, if
 *    they balance.
 */
class QuickAdd(
    private val styles: Styles,
    private val suggestions: EntrySuggestions? = null,
    private val templates: List<Template> = emptyList(),
    private val today: LocalDate = LocalDate.now(),
    private val declaredPayeesOnly: Boolean = false,
) {
    private val defaultCommodity get() = suggestions?.defaultCommodity.orEmpty()

    fun start(date: LocalDate = today) =
        QuickAddState(TransactionDraft(date, postings = emptyList()))

    /**
     * Start with [template] already chosen, dated [date]: as if the date and the template had been
     * answered, so the first prompt is the template's first account, and Back still goes to the payee
     * and the date.
     */
    fun startWith(template: Template, date: LocalDate = today): QuickAddState {
        val dated = (submit(start(date), date.toString()) as QuickResult.Next).state
        return (submit(dated, template.name, QuickCompletion(template.name, template)) as QuickResult.Next).state
    }

    fun prompt(s: QuickAddState): QuickPrompt = when (val step = s.step) {
        QuickStep.Date -> QuickPrompt("Date", s.draft.date.toString(), "eg 11-02, yesterday or -2")
        QuickStep.Payee -> QuickPrompt(
            "Payee",
            null,
            if (templates.isEmpty()) "payee | note adds a note" else "Tab for payees and templates; payee | note adds a note",
        )
        is QuickStep.Account -> QuickPrompt(
            "Account ${step.index + 1}",
            templatePosting(s, step.index)?.account?.takeIf { it.isNotBlank() },
            if (step.index >= 2) "Enter with nothing, or ., to finish" else "Tab for accounts",
        )
        is QuickStep.Amount -> QuickPrompt(
            "Amount ${step.index + 1}",
            defaultAmount(s, step.index),
            if (step.index == 0) "eg 4.50, \$4.50 or 4.50 EUR" else "Enter for the amount that balances",
        )
        QuickStep.Confirm -> QuickPrompt("Add this?", "yes", "Enter adds it")
    }

    /** Suggestions for what's typed at the current prompt, best first. */
    fun completions(s: QuickAddState, text: String, limit: Int = 8): List<QuickCompletion> = when (s.step) {
        QuickStep.Date -> listOf(today, today.minusDays(1), today.minusDays(2))
            .map { it.toString() }.filter { it.startsWith(text.trim()) && it != text.trim() }.map { QuickCompletion(it) }
        QuickStep.Payee -> payeeCompletions(text.trim(), limit)
        is QuickStep.Account -> suggestions?.accountsMatching(text, limit).orEmpty().map { QuickCompletion(it) }
        else -> emptyList()
    }

    /** Templates first (the first few when nothing's typed), then payees. */
    private fun payeeCompletions(t: String, limit: Int): List<QuickCompletion> {
        val fromTemplates = if (t.isEmpty()) templates.take(3) else templatesMatching(templates, t, 3)
        val payees = suggestions?.payeesMatching(t, limit, declaredOnly = declaredPayeesOnly).orEmpty()
        return (fromTemplates.map { QuickCompletion(it.name, it) } + payees.map { QuickCompletion(it) }).take(limit)
    }

    /** Answer the current prompt with [text], or with [chosen] if that's the suggestion picked. */
    fun submit(s: QuickAddState, text: String, chosen: QuickCompletion? = null): QuickResult {
        val answer = text.trim()
        val p = prompt(s)
        val given = answer.ifEmpty { p.default.orEmpty() }
        return when (val step = s.step) {
            QuickStep.Date -> {
                val date = parseDate(given, today) ?: return QuickResult.Problem("Not a date I can read, like 2026-11-02 or yesterday")
                next(s, s.copy(draft = s.draft.copy(date = date), step = QuickStep.Payee), "Date" to date.toString())
            }

            QuickStep.Payee -> {
                val template = chosen?.template?.takeIf { chosen.value == answer }
                if (template != null) {
                    val draft = template.draft.copy(date = s.draft.date)
                    return next(
                        s, s.copy(draft = draft.copy(postings = emptyList()), step = QuickStep.Account(0), template = template),
                        "Template" to "${template.name}: ${draft.description.ifEmpty { "no payee" }}",
                    )
                }
                val payee = answer.substringBefore('|').trim()
                val note = if ('|' in answer) answer.substringAfter('|').trim() else ""
                val draft = s.draft.copy(payee = payee, note = note)
                Entry.check(draft.copy(postings = listOf(DraftPosting("a", "0"), DraftPosting("b", "0"))), styles)
                    .errors.firstOrNull { it.startsWith("The payee") || it.startsWith("The note") }
                    ?.let { return QuickResult.Problem(it) }
                next(s, s.copy(draft = draft, step = QuickStep.Account(0)), "Payee" to draft.description.ifEmpty { "(none)" })
            }

            is QuickStep.Account -> {
                val i = step.index
                val finish = answer == "." || (given.isEmpty() && i >= 2)
                if (finish) {
                    if (i < 2) return QuickResult.Problem("A transaction needs at least two accounts")
                    val draft = s.draft.copy(postings = s.draft.postings.take(i))
                    Entry.check(draft, styles).firstProblem?.let { return QuickResult.Problem(it) }
                    return next(s, s.copy(draft = draft, step = QuickStep.Confirm), "" to "")
                }
                if (given.isEmpty()) return QuickResult.Problem("Type an account, or Tab through the suggestions")
                if (given.any { it.isWhitespace() && it != ' ' } || given.contains("  ")) {
                    return QuickResult.Problem("An account can't contain two spaces or a tab")
                }
                val postings = s.draft.postings.take(i) + DraftPosting(account = given)
                next(s, s.copy(draft = s.draft.copy(postings = postings), step = QuickStep.Amount(i)), "Account ${i + 1}" to given)
            }

            is QuickStep.Amount -> {
                val i = step.index
                if (given.isEmpty()) return QuickResult.Problem("Type an amount, like 4.50")
                val (amount, commodity) = splitAmount(given)
                    ?: return QuickResult.Problem("Put the currency before or after the number, not both")
                if (Entry.parseAmount(amount) == null) {
                    return QuickResult.Problem(if (Entry.isExpression(amount)) "Not a sum this can work out, like 12.50+3.20" else "Not a number")
                }
                val c = commodity ?: currencyFor(s, i)
                val postings = s.draft.postings.take(i) + s.draft.postings[i].copy(amount = amount, commodity = c)
                next(s, s.copy(draft = s.draft.copy(postings = postings), step = QuickStep.Account(i + 1)),
                    "Amount ${i + 1}" to show(amount, c))
            }

            QuickStep.Confirm -> when (answer.lowercase()) {
                "", "y", "yes" -> QuickResult.Done(s.draft)
                "n", "no" -> QuickResult.Next(back(s) ?: s)
                else -> QuickResult.Problem("Enter adds it; Back changes it")
            }
        }
    }

    /** The state before the last answer, or null at the first prompt. */
    fun back(s: QuickAddState): QuickAddState? = s.previous

    private fun next(from: QuickAddState, to: QuickAddState, line: Pair<String, String>) =
        QuickResult.Next(to.copy(log = if (line.first.isEmpty()) from.log else from.log + line, previous = from))

    private fun templatePosting(s: QuickAddState, i: Int): DraftPosting? = s.template?.draft?.postings?.getOrNull(i)

    /** The template's amount for posting [i], or else (from the second on) what balances the ones before. */
    private fun defaultAmount(s: QuickAddState, i: Int): String? {
        templatePosting(s, i)?.takeIf { it.amount.isNotBlank() }?.let { return show(it.amount, it.commodity) }
        if (i == 0) return null
        val sums = LinkedHashMap<String, BigDecimal>()
        for (p in s.draft.postings.take(i)) {
            val q = Entry.parseAmount(p.amount) ?: return null
            sums.merge(p.commodity.trim(), q, BigDecimal::add)
        }
        val left = sums.filterValues { it.signum() != 0 }
        if (left.size != 1) return null
        val (c, q) = left.entries.single()
        val st = styles.styleFor(c)
        return show(Entry.amountInput(q.negate(), st), c)
    }

    private fun currencyFor(s: QuickAddState, i: Int): String =
        templatePosting(s, i)?.commodity?.takeIf { it.isNotBlank() }
            ?: s.draft.postings.take(i).lastOrNull { it.commodity.isNotBlank() }?.commodity
            ?: defaultCommodity

    /** An amount as it's shown at the prompt and in the answers: $4.50, $-4.50, or 4.50 EUR. */
    private fun show(amount: String, commodity: String): String {
        val c = commodity.trim()
        if (c.isEmpty()) return amount
        // Like the journal writes it: $-4.50 with the symbol on the left, -4.50 EUR on the right.
        return if (styles.styleFor(c).side == Side.LEFT) "$c$amount" else "$amount $c"
    }

    companion object {
        /**
         * A typed amount split into the number (or sum) and the currency before or after it, if any:
         * "$4.50" → ("4.50", "$"), "-$4.50" → ("-4.50", "$"), "4.50 EUR" → ("4.50", "EUR"), "12+3" →
         * ("12+3", null). Null when there's a currency both before and after.
         */
        fun splitAmount(text: String): Pair<String, String?>? {
            var s = text.trim()
            var negative = false
            if (s.length > 1 && (s[0] == '-' || s[0] == '\u2212') && !s[1].isDigit() && s[1] != '.' && s[1] != ',' && s[1] != '(') {
                negative = true
                s = s.substring(1).trimStart()
            }
            fun isNumberChar(c: Char) = c.isDigit() || c in "+-\u2212*/×÷.,() "
            val before = s.takeWhile { !isNumberChar(it) }
            s = s.drop(before.length).trimStart()
            val after = s.takeLastWhile { !isNumberChar(it) }
            s = s.dropLast(after.length).trim()
            if (before.isNotBlank() && after.isNotBlank()) return null
            val commodity = before.trim().ifEmpty { after.trim() }.ifEmpty { null }
            return (if (negative) "-$s" else s) to commodity
        }

        /**
         * A typed date: 2026-11-02 or 2026/11/02 (or with dots), 11-02 (this year), 2 (this month), today,
         * yesterday, tomorrow, or a number of days back or ahead: -3, +1. Null if it isn't one of these.
         */
        fun parseDate(text: String, today: LocalDate): LocalDate? {
            val t = text.trim().lowercase()
            when (t) {
                "", "today", "t" -> return today
                "yesterday", "y" -> return today.minusDays(1)
                "tomorrow" -> return today.plusDays(1)
            }
            Regex("^([+-])(\\d{1,4})$").find(t)?.let { m ->
                val n = m.groupValues[2].toLong()
                return if (m.groupValues[1] == "-") today.minusDays(n) else today.plusDays(n)
            }
            val parts = t.split('-', '/', '.')
            if (parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return null
            return try {
                when (parts.size) {
                    3 -> LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                    2 -> LocalDate.of(today.year, parts[0].toInt(), parts[1].toInt())
                    1 -> if (parts[0].length <= 2) today.withDayOfMonth(parts[0].toInt()) else null
                    else -> null
                }
            } catch (e: java.time.DateTimeException) {
                null
            } catch (e: DateTimeParseException) {
                null
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
