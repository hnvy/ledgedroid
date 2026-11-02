package app.ledgedroid.core

import java.time.LocalDate

/**
 * hledger command-line options typed into the report filter, alongside the query terms, eg
 *
 *     --auto --budget register budget not:<unbudgeted> -M -H
 *     bs -Q --depth 2
 *     --forecast -M expenses
 *
 * Options set here take precedence over the app's own controls; fields left null (or false) leave those
 * controls in charge. Everything that isn't an option is kept, in order, as a query term.
 *
 * Supported:
 *   bal, balance, bs, balancesheet, is, incomestatement   as the first word: which report
 *   --balancesheet, --incomestatement, --balance           the same, anywhere
 *   --budget[=TEXT]            budget report, optionally only rules whose description contains TEXT
 *   -W -M -Q -Y                weekly .. yearly columns (--weekly, --monthly, --quarterly, --yearly)
 *   -H, --historical           balances at the end of each period
 *   --auto                     apply auto posting rules
 *   --forecast[=PERIOD]        add transactions generated from periodic rules
 *   -p, --period PERIOD        eg -p 2026, -p "monthly in 2026", -p 2026-01..2026-07
 *   -b, --begin DATE / -e, --end DATE
 *   --depth N, -N              show at most N account levels
 *   --drop N                   leave out the first N parts of account names (list mode)
 *   -t, --tree / -l, --flat    tree or list
 *   -E, --empty                show zero balances (unbudgeted accounts, for --budget)
 *   -S, --sort-amount          sort by amount
 *   -B, --cost                 convert to cost
 *   --invert                   flip signs
 *   -U, -P, -C                 only unmarked, pending or cleared (--unmarked, --pending, --cleared);
 *                              several are ORed, as in hledger
 *   -T, -A                     accepted; totals and averages are always shown
 *
 * Options taking a value accept it as the next word or after =, eg --depth 2 or --depth=2, and the short
 * ones also joined, eg -b2026.
 */
data class ReportCommand(
    val kind: ReportKind? = null,
    /** --budget=TEXT; empty for plain --budget. Only meaningful with kind BUDGET. */
    val budgetPattern: String = "",
    val interval: ReportInterval? = null,
    val historical: Boolean = false,
    val auto: Boolean = false,
    /** null without --forecast; empty for --forecast with no period. */
    val forecast: String? = null,
    /** -b/-e/-p dates; null if none were given. Either end may be open. */
    val span: DateSpan? = null,
    val depth: Int? = null,
    val drop: Int? = null,
    val mode: AccountListMode? = null,
    val empty: Boolean = false,
    val sortByAmount: Boolean = false,
    val cost: Boolean = false,
    val invert: Boolean = false,
    /** The query terms, including status: terms for -U/-P/-C, ready for [Query.parseTerms]. */
    val queryTerms: List<String> = emptyList(),
    /** The first problem found, eg an unknown option; the rest of the text is still used. */
    val error: String? = null,
    /** "unmarked", "pending", "cleared" for -U/-P/-C, in the order given, for describing them. */
    val statusFlags: List<String> = emptyList(),
) {
    /** True if any option was given (not just query terms). */
    val hasOptions: Boolean
        get() = kind != null || interval != null || historical || auto || forecast != null || span != null ||
                depth != null || drop != null || mode != null || empty || sortByAmount || cost || invert ||
                statusFlags.isNotEmpty()

    /** A short description of the options, eg ["budget", "monthly", "running balances", "auto postings"]. */
    fun describe(): List<String> = buildList {
        when (kind) {
            ReportKind.BUDGET -> add(if (budgetPattern.isEmpty()) "budget" else "budget rules matching \"$budgetPattern\"")
            ReportKind.BALANCE_SHEET -> add("balance sheet")
            ReportKind.INCOME_STATEMENT -> add("income statement")
            ReportKind.BALANCE -> add("accounts")
            null -> Unit
        }
        interval?.let { add(it.label.lowercase()) }
        if (historical) add("running balances")
        if (auto) add("auto postings")
        forecast?.let { add(if (it.isEmpty()) "forecast" else "forecast $it") }
        span?.let { add(describeSpan(it)) }
        depth?.let { add(if (it == 1) "1 level" else "$it levels") }
        drop?.let { add("drop $it") }
        mode?.let { add(if (it == AccountListMode.TREE) "tree" else "list") }
        if (empty) add("-E")
        if (sortByAmount) add("by amount")
        if (cost) add("at cost")
        if (invert) add("inverted")
        if (statusFlags.isNotEmpty()) add(statusFlags.joinToString(" or "))
    }

    companion object {
        /** Short options without a value, which can be grouped. */
        private const val GROUPABLE = "WMQYHtlESBUPCTA"

        private val COMMANDS = mapOf(
            "bal" to ReportKind.BALANCE, "balance" to ReportKind.BALANCE,
            "bs" to ReportKind.BALANCE_SHEET, "balancesheet" to ReportKind.BALANCE_SHEET,
            "is" to ReportKind.INCOME_STATEMENT, "incomestatement" to ReportKind.INCOME_STATEMENT,
        )

        /** Parse the filter text. Never throws: problems are reported in [error]. */
        fun parse(text: String, today: LocalDate = LocalDate.now()): ReportCommand =
            parseWords(Query.tokenize(text), today)

        fun parseWords(input: List<String>, today: LocalDate = LocalDate.now()): ReportCommand {
            // Short flags can be grouped, eg -MHE for -M -H -E.
            var words = input.flatMap { w ->
                if (Regex("^-[A-Za-z]{2,}$").matches(w) && w.drop(1).all { it in GROUPABLE }) w.drop(1).map { "-$it" }
                else listOf(w)
            }
            // "hledger bal ..." or "bs ...": a command name only counts as the first word.
            if (words.firstOrNull() == "hledger") words = words.drop(1)
            var kind: ReportKind? = words.firstOrNull()?.let { COMMANDS[it] }
            if (kind != null) words = words.drop(1)

            var budgetPattern = ""
            var interval: ReportInterval? = null
            var historical = false
            var auto = false
            var forecast: String? = null
            var begin: LocalDate? = null
            var end: LocalDate? = null
            var datesGiven = false
            var depth: Int? = null
            var drop: Int? = null
            var mode: AccountListMode? = null
            var empty = false
            var sort = false
            var cost = false
            var invert = false
            val terms = ArrayList<String>()
            val status = ArrayList<String>()
            val statusTerms = ArrayList<String>()
            var error: String? = null
            fun problem(message: String) {
                if (error == null) error = message
            }

            var i = 0
            while (i < words.size) {
                val w = words[i++]
                if (!w.startsWith("-") || w == "-" || w == "--") {
                    // A lone - or -- is an option still being typed.
                    if (!w.startsWith("-")) terms.add(w)
                    continue
                }
                val long = w.startsWith("--")
                val name = if (long) w.substringBefore('=') else w.take(2)
                // The option's value: after =, joined to a short option (-b2026), or the next word.
                val attached: String? = when {
                    long && w.contains('=') -> w.substringAfter('=')
                    !long && w.length > 2 -> w.substring(2).removePrefix("=")
                    else -> null
                }

                fun value(): String? = attached ?: words.getOrNull(i)?.also { i++ }
                    ?: run { problem("$name needs a value, eg ${example(name)}"); null }

                fun noValue(): Boolean {
                    if (attached != null) {
                        problem("$name doesn't take a value"); return false
                    }
                    return true
                }

                // -1 .. -99: depth, like hledger
                if (!long && w.drop(1).all { it.isDigit() }) {
                    depth = w.drop(1).toInt().takeIf { it > 0 } ?: run { problem("depth must be 1 or more"); depth }
                    continue
                }
                when (name) {
                    "--budget" -> {
                        kind = ReportKind.BUDGET; budgetPattern = attached?.trim().orEmpty()
                    }

                    "--balancesheet", "--bs" -> if (noValue()) kind = ReportKind.BALANCE_SHEET
                    "--incomestatement", "--is" -> if (noValue()) kind = ReportKind.INCOME_STATEMENT
                    "--balance", "--bal" -> if (noValue()) kind = ReportKind.BALANCE
                    "-W", "--weekly" -> if (noValue()) interval = ReportInterval.WEEKLY
                    "-M", "--monthly" -> if (noValue()) interval = ReportInterval.MONTHLY
                    "-Q", "--quarterly" -> if (noValue()) interval = ReportInterval.QUARTERLY
                    "-Y", "--yearly" -> if (noValue()) interval = ReportInterval.YEARLY
                    "-D", "--daily" -> problem("Daily columns aren't supported; use -W, -M, -Q or -Y")
                    "-H", "--historical" -> if (noValue()) historical = true
                    "--cumulative" -> problem("--cumulative isn't supported; -H shows balances at the end of each period")
                    "--auto" -> if (noValue()) auto = true
                    "--forecast" -> {
                        val p = attached?.trim().orEmpty()
                        if (p.isNotEmpty()) {
                            try {
                                parsePeriodExpr(p)
                            } catch (e: IllegalArgumentException) {
                                problem("--forecast: ${e.message}")
                            }
                        }
                        forecast = p
                    }

                    "-p", "--period" -> value()?.let { v ->
                        try {
                            val expr = parsePeriodExpr(v)
                            expr.interval?.let { iv ->
                                interval = ReportInterval.entries.firstOrNull { it.interval == iv }
                                    ?: run { problem("-p: only weekly, monthly, quarterly or yearly columns are supported"); interval }
                            }
                            if (!expr.span.isOpen) {
                                begin = expr.span.start; end = expr.span.end; datesGiven = true
                            }
                        } catch (e: IllegalArgumentException) {
                            problem("-p: ${e.message}")
                        }
                    }

                    "-b", "--begin" -> value()?.let { v ->
                        date(v)?.let { begin = it; datesGiven = true } ?: problem("-b: \"$v\" isn't a date, eg -b 2026-01")
                    }

                    "-e", "--end" -> value()?.let { v ->
                        date(v)?.let { end = it; datesGiven = true } ?: problem("-e: \"$v\" isn't a date, eg -e 2026-07")
                    }

                    "--depth" -> value()?.let { v ->
                        v.toIntOrNull()?.takeIf { it > 0 }?.let { depth = it } ?: problem("--depth needs a number of 1 or more")
                    }

                    "--drop" -> value()?.let { v ->
                        v.toIntOrNull()?.takeIf { it >= 0 }?.let { drop = it } ?: problem("--drop needs a number, eg --drop 1")
                    }

                    "-t", "--tree" -> if (noValue()) mode = AccountListMode.TREE
                    "-l", "--flat", "--list" -> if (noValue()) mode = AccountListMode.FLAT
                    "-E", "--empty" -> if (noValue()) empty = true
                    "-S", "--sort-amount" -> if (noValue()) sort = true
                    "-B", "--cost" -> if (noValue()) cost = true
                    "--invert" -> if (noValue()) invert = true
                    "-U", "--unmarked" -> if (noValue()) {
                        status.add("unmarked"); statusTerms.add("status:")
                    }

                    "-P", "--pending" -> if (noValue()) {
                        status.add("pending"); statusTerms.add("status:!")
                    }

                    "-C", "--cleared" -> if (noValue()) {
                        status.add("cleared"); statusTerms.add("status:*")
                    }

                    "-T", "--row-total", "-A", "--average" -> Unit // always shown with columns
                    "-V", "-X", "--value", "--market", "--exchange" ->
                        problem("Market values aren't supported (the journal's P prices are skipped); -B converts to cost")

                    else -> problem("$name isn't an option Ledgedroid knows. Tap ⓘ for the list.")
                }
            }
            val b = begin
            val e = end
            if (b != null && e != null && !b.isBefore(e)) {
                problem("The end date must be after the start (the end isn't included)")
            }
            return ReportCommand(
                kind = kind,
                budgetPattern = budgetPattern,
                interval = interval,
                historical = historical,
                auto = auto,
                forecast = forecast,
                span = if (datesGiven) DateSpan(b, e) else null,
                depth = depth,
                drop = drop,
                mode = mode,
                empty = empty,
                sortByAmount = sort,
                cost = cost,
                invert = invert,
                queryTerms = terms + statusTerms.distinct(),
                error = error,
                statusFlags = status.distinct(),
            )
        }

        /** A date for -b/-e: the start of the year, quarter, month or day written (2026, 2026q3, 2026-10, 2026-10-27). */
        private fun date(s: String): LocalDate? = periodDate(s.lowercase())?.start

        private fun example(option: String) = when (option) {
            "-p", "--period" -> "$option 2026"
            "-b", "--begin" -> "$option 2026-01"
            "-e", "--end" -> "$option 2026-07"
            else -> "$option 2"
        }
    }
}
