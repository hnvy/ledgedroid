package app.ledgedroid.core

import java.time.LocalDate

/** hledger's --forecast: transactions generated from periodic rules (~), added to the journal for reports. */
object Forecast {

    /**
     * The dates to generate forecast transactions in, like hledger:
     *  - start: the start given in [expr], else the later of the report start (if any) and the day after
     *    the latest ordinary transaction (or today if there are none);
     *  - end: the end given in [expr], else the report end (if any), else 180 days from today.
     * [expr] is the period after --forecast=, or empty.
     */
    fun span(journal: Journal, expr: String, reportSpan: DateSpan, today: LocalDate): DateSpan {
        val given = if (expr.isBlank()) DateSpan.ALL else parsePeriodExpr(expr).span
        val afterLast = journal.transactions.lastOrNull { !it.forecast }?.date?.plusDays(1) ?: today
        val start = given.start ?: listOfNotNull(reportSpan.start, afterLast).max()
        val end = given.end ?: reportSpan.end ?: today.plusDays(180)
        return DateSpan(start, end)
    }

    /**
     * The journal with forecast transactions in [span] added, in date order after any ordinary transactions
     * on the same day. They get auto postings if the journal has them applied (--auto), and indices after
     * the journal's own so each can be opened.
     */
    fun extend(journal: Journal, span: DateSpan): Journal {
        val start = span.start ?: return journal
        val end = span.end ?: return journal
        if (journal.periodicRules.isEmpty() || !start.isBefore(end)) return journal
        var generated = periodicTransactions(journal.periodicRules, span, journal.styles)
        if (journal.autoApplied && journal.autoRules.isNotEmpty()) {
            val withAuto = generated.map { applyAutoRules(it, journal.autoRules).toRaw() }
            generated = balanceTransactions(withAuto, journal.styles, emptyList(), checkAssertions = false).first
        }
        if (generated.isEmpty()) return journal
        val base = (journal.transactions.maxOfOrNull { it.index } ?: -1) + 1
        val forecast = generated.map { it.copy(index = base + it.index, forecast = true) }
        // A stable sort keeps the journal's own order, with forecasts after ordinary transactions on a day.
        return journal.copy(transactions = (journal.transactions + forecast).sortedBy { it.date })
    }
}

// ---------------------------------------------------------------------- --drop

/** Account names shown without their first [n] parts (--drop), for list mode. */
fun BalanceReport.dropNames(n: Int): BalanceReport =
    copy(rows = rows.map { it.copy(displayName = dropAccountName(n, it.fullName)) })

fun CompoundReport.dropNames(n: Int): CompoundReport =
    copy(sections = sections.map { it.copy(report = it.report.dropNames(n)) })

fun MultiPeriodReport.dropNames(n: Int): MultiPeriodReport =
    copy(rows = rows.map { it.copy(displayName = dropAccountName(n, it.fullName)) })

fun MultiPeriodCompound.dropNames(n: Int): MultiPeriodCompound =
    copy(sections = sections.map { it.copy(report = it.report.dropNames(n)) })

fun BudgetReport.dropNames(n: Int): BudgetReport =
    copy(rows = rows.map { it.copy(displayName = dropAccountName(n, it.fullName)) })

// ---------------------------------------------------------------------- one period at a time

/**
 * [rows] without the accounts that are zero in a period along with all their subaccounts, keeping the
 * tree's shape: a zero parent stays if a subaccount has an amount. In a list, every row stands alone.
 */
fun <R> withoutEmptySubtrees(rows: List<R>, indent: (R) -> Int, isZero: (R) -> Boolean): List<R> {
    val keep = BooleanArray(rows.size)
    for (i in rows.indices.reversed()) {
        if (!isZero(rows[i])) {
            keep[i] = true; continue
        }
        var j = i + 1
        while (j < rows.size && indent(rows[j]) > indent(rows[i])) {
            if (keep[j]) {
                keep[i] = true; break
            }
            j++
        }
    }
    return rows.filterIndexed { i, _ -> keep[i] }
}
