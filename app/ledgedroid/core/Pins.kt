package app.ledgedroid.core

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Pinned accounts are shown at the top of a report with their own total (including subaccounts), wherever
 * they are in the tree, and highlighted in their place in it.
 */
object Pins {

    /**
     * A pinned account's amount in a single-period report: like its tree-mode row, including subaccounts,
     * with the report's query and options. In a balance sheet or income statement, the account must belong
     * to one of its sections (its section is returned, and the amount has that section's sign); otherwise
     * null, as it isn't part of that report.
     */
    fun single(
        journal: Journal,
        query: Query,
        opts: ReportOptions,
        kind: ReportKind,
        account: String,
    ): Pair<CompoundSection?, MixedAmount>? {
        if (account.startsWith(UNBUDGETED)) return null
        if (kind == ReportKind.BALANCE || kind == ReportKind.BUDGET) {
            val r = Reports.balance(journal, query, opts, accountFilter = { isSameOrSubaccount(it, account) })
            return null to r.total
        }
        val shape = Reports.compoundShape(kind)
        val s = shape.sections.firstOrNull { journal.accountType(account) in it.types } ?: return null
        val r = Reports.balance(
            journal, query, opts.copy(accumulation = shape.accumulation),
            accountFilter = { isSameOrSubaccount(it, account) && journal.accountType(it) in s.types },
            sign = s.sign,
        )
        return s to r.total
    }

    /** As [single], with a column per period of [periods]; the row has the account's name, total and average. */
    fun multi(
        journal: Journal,
        query: Query,
        opts: ReportOptions,
        interval: ReportInterval,
        kind: ReportKind,
        periods: List<DateSpan>,
        account: String,
    ): Pair<CompoundSection?, MultiPeriodRow>? {
        if (account.startsWith(UNBUDGETED)) return null
        fun row(r: MultiPeriodReport) = r.totals.copy(fullName = account, displayName = account)
        if (kind == ReportKind.BALANCE || kind == ReportKind.BUDGET) {
            val r = MultiPeriodReports.balance(
                journal, query, opts, interval, accountFilter = { isSameOrSubaccount(it, account) }, periods = periods,
            )
            return null to row(r)
        }
        val shape = Reports.compoundShape(kind)
        val s = shape.sections.firstOrNull { journal.accountType(account) in it.types } ?: return null
        val r = MultiPeriodReports.balance(
            journal, query, opts.copy(accumulation = shape.accumulation), interval,
            accountFilter = { isSameOrSubaccount(it, account) && journal.accountType(it) in s.types },
            sign = s.sign, periods = periods,
        )
        return s to row(r)
    }

    /**
     * A pinned account's row in a budget report: its own row, or (with -E) its row under <unbudgeted>.
     * Null if the report has no row for it, eg an unbudgeted account lumped into <unbudgeted>.
     */
    fun budget(report: BudgetReport, account: String): BudgetRow? =
        report.rows.firstOrNull { it.fullName == account }
            ?: report.rows.firstOrNull { it.fullName == "$UNBUDGETED:$account" }

    /** True if [account] or an account it's inside is in [hidden]. */
    fun isHidden(account: String, hidden: Set<String>): Boolean {
        var a: String? = account.removePrefix("$UNBUDGETED:")
        while (a != null) {
            if (a in hidden) return true
            a = parentAccount(a)
        }
        return account in hidden
    }
}

/**
 * For amount bars: each row's amount as a share (0 to 1) of the one it's part of. In a tree that's its
 * parent row (the nearest row above with less indent); top-level rows, and every row in a list, are
 * compared with the largest of them. Amounts are compared in the report's main commodity (the one most
 * rows have), by size, so negative amounts get bars too. Null where there's nothing to compare.
 */
fun <R> shareOfParent(rows: List<R>, tree: Boolean, indent: (R) -> Int, amount: (R) -> MixedAmount): List<Float?> {
    val commodity = rows.flatMap { amount(it).parts.keys }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ?: return rows.map { null }
    val size = rows.map { amount(it).quantityOf(commodity).abs() }
    val topMax = rows.indices.filter { !tree || indent(rows[it]) == 0 }.maxOfOrNull { size[it] } ?: BigDecimal.ZERO
    val out = ArrayList<Float?>(rows.size)
    // The rows above, by indent, to find each row's parent.
    val stack = ArrayList<Int>()
    for (i in rows.indices) {
        val d = indent(rows[i])
        while (stack.isNotEmpty() && indent(rows[stack.last()]) >= d) stack.removeAt(stack.lastIndex)
        val whole = if (!tree || stack.isEmpty()) topMax else size[stack.last()]
        out.add(
            if (whole.signum() == 0) null
            else size[i].divide(whole, 4, RoundingMode.HALF_EVEN).toFloat().coerceIn(0f, 1f)
        )
        if (tree) stack.add(i)
    }
    return out
}

// ---------------------------------------------------------------------- hiding rows only

/*
 * With "Keep hidden accounts in totals" on, hiding an account only takes its rows (and its subaccounts')
 * out of the report: every amount, parent account and total stays as if nothing were hidden.
 */

/** Is the row for [fullName] hidden by [hidden]: the account, an account it's inside, or <unbudgeted> for its rows. */
fun isHiddenRow(fullName: String, hidden: Set<String>): Boolean =
    Pins.isHidden(fullName, hidden) || (UNBUDGETED in hidden && fullName.startsWith("$UNBUDGETED:"))

fun BalanceReport.withoutHiddenRows(hidden: Set<String>): BalanceReport =
    if (hidden.isEmpty()) this else copy(rows = rows.filterNot { isHiddenRow(it.fullName, hidden) })

fun CompoundReport.withoutHiddenRows(hidden: Set<String>): CompoundReport =
    if (hidden.isEmpty()) this else copy(sections = sections.map { it.copy(report = it.report.withoutHiddenRows(hidden)) })

fun MultiPeriodReport.withoutHiddenRows(hidden: Set<String>): MultiPeriodReport =
    if (hidden.isEmpty()) this else copy(rows = rows.filterNot { isHiddenRow(it.fullName, hidden) })

fun MultiPeriodCompound.withoutHiddenRows(hidden: Set<String>): MultiPeriodCompound =
    if (hidden.isEmpty()) this else copy(sections = sections.map { it.copy(report = it.report.withoutHiddenRows(hidden)) })

fun BudgetReport.withoutHiddenRows(hidden: Set<String>): BudgetReport =
    if (hidden.isEmpty()) this else copy(rows = rows.filterNot { isHiddenRow(it.fullName, hidden) })
