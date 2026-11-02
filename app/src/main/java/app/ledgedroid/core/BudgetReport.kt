package app.ledgedroid.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** The account name hledger uses for actual amounts in accounts that have no budget goal. */
const val UNBUDGETED = "<unbudgeted>"

enum class ReportInterval(val label: String, val interval: Interval, val unit: String) {
    WEEKLY("Weekly", Interval.Weeks(1), "week"),
    MONTHLY("Monthly", Interval.Months(1), "month"),
    QUARTERLY("Quarterly", Interval.Quarters(1), "quarter"),
    YEARLY("Yearly", Interval.Years(1), "year"),
}

data class BudgetOptions(
    val interval: ReportInterval = ReportInterval.MONTHLY,
    /** Requested dates; open ends are filled from the journal's first and last transactions. */
    val span: DateSpan = DateSpan.ALL,
    /** -H: each period shows balances at its end, including everything before it (actual and goals). */
    val historical: Boolean = false,
    val depth: Int? = null,
    /** -E: show unbudgeted accounts by name (under <unbudgeted>) instead of lumping them together. */
    val showUnbudgeted: Boolean = false,
    val invert: Boolean = false,
    val atCost: Boolean = false,
    /**
     * --tree. Amounts are always inclusive of subaccounts. In list mode (hledger's default) rows with no
     * amounts of their own are hidden and full names shown; in tree mode only "boring" parents (zero, with
     * fewer than two interesting subaccounts) are folded into their child's name.
     */
    val tree: Boolean = false,
    /** --budget=PATTERN: use only periodic rules whose description contains this (case-insensitive). */
    val descriptionPattern: String = "",
)

/** One period of one row. goal is null where the account has no goal to show (like hledger's blank goal). */
data class BudgetCell(val actual: MixedAmount, val goal: MixedAmount?)

data class BudgetRow(
    val fullName: String,
    val displayName: String,
    val indent: Int,
    val cells: List<BudgetCell>,
) {
    val isUnbudgeted get() = fullName == UNBUDGETED || fullName.startsWith("$UNBUDGETED:")
}

data class BudgetReport(
    val periods: List<DateSpan>,
    val rows: List<BudgetRow>,
    val totals: List<BudgetCell>,
    /** How many periodic rules were used for goals (after the description pattern). */
    val ruleCount: Int,
)

object Budgets {

    /**
     * hledger's budgetReport, for a tree-mode report with a report interval:
     *
     * 1. Accounts posted to by any periodic rule in the report period, and their parents, are "budgeted".
     * 2. Actual postings to other accounts are moved to their nearest budgeted parent, or to <unbudgeted>
     *    (with -E, to <unbudgeted>:ACCOUNT, or kept as is under a budgeted parent).
     * 3. Goals are the postings of transactions generated from the periodic rules, from the start of the
     *    journal (so -H goals accumulate) to the report end, with missing amounts inferred.
     * 4. The query applies to actual postings after step 2; for goals only its account and cur: terms apply.
     * 5. Rows are laid out like the balance report (see [BudgetOptions.tree]); a row shows a goal if it or
     *    a subaccount has one.
     */
    fun report(journal: Journal, query: Query, opts: BudgetOptions): BudgetReport {
        val rules = journal.periodicRules.filter {
            opts.descriptionPattern.isEmpty() || it.description.lowercase()
                .contains(opts.descriptionPattern.lowercase())
        }
        val reqStart = opts.span.start ?: journal.firstDate
        val reqEnd = opts.span.end ?: journal.lastDate?.plusDays(1)
        if (reqStart == null || reqEnd == null || !reqStart.isBefore(reqEnd)) {
            return BudgetReport(emptyList(), emptyList(), emptyList(), rules.size)
        }
        // Like hledger, periods start at an interval boundary unless a start date was given explicitly.
        var periods = splitSpan(opts.interval.interval, reqStart, reqEnd, adjust = opts.span.start == null)
        var start = periods.first().start!!
        val end = periods.last().end!!
        val reportSpan = DateSpan(start, end)
        val depth = listOfNotNull(opts.depth, query.depth).minOrNull()

        // 1. budgeted accounts (from all rules, like hledger, not just those matching the pattern)
        val budgeted = HashSet<String>()
        for (r in journal.periodicRules) {
            if (r.dates(reportSpan).isEmpty()) continue
            for (p in r.postings) {
                var a: String? = p.account
                while (a != null) {
                    budgeted.add(a); a = parentAccount(a)
                }
            }
        }

        // 3. goal transactions, from the interval boundary before the journal/report start
        val goalTxns = if (rules.isEmpty()) emptyList() else {
            val d = listOfNotNull(journal.firstDate, start).min()
            val longest = rules.maxBy { it.interval.approxDays }
            val intervalStart = longest.interval.boundaryOnOrBefore(d)
            val ruleStart = longest.span.start
            val goalStart = if (ruleStart == null) intervalStart else minOf(d, maxOf(intervalStart, ruleStart))
            periodicTransactions(rules, DateSpan(goalStart, end), journal.styles)
        }

        // With no explicit start, the periods also cover the goals (hledger unions the two reports'
        // periods), which matters when goals start earlier, eg monthly goals in a weekly report.
        val firstGoal = goalTxns.minOfOrNull { it.date }
        if (opts.span.start == null && firstGoal != null && firstGoal.isBefore(start)) {
            periods = splitSpan(opts.interval.interval, firstGoal, end, adjust = true)
            start = periods.first().start!!
        }

        val n = periods.size
        val root = Node("", n)
        val index = HashMap<String, Node>()
        fun node(name: String): Node = index.getOrPut(name) {
            val parent = parentAccount(name)?.let { node(it) } ?: root
            Node(name, n).also { parent.children.add(it) }
        }

        fun column(d: LocalDate): Int {
            if (d.isBefore(start)) return -1
            val i = periods.binarySearch { p -> if (p.end!! <= d) -1 else if (p.start!! > d) 1 else 0 }
            return if (i >= 0) i else n
        }

        // 2 + 4. actual postings
        fun remap(a: String): String = when {
            a in budgeted -> a
            else -> {
                var p = parentAccount(a)
                while (p != null && p !in budgeted) p = parentAccount(p)
                when {
                    p != null -> if (opts.showUnbudgeted) a else p
                    opts.showUnbudgeted -> "$UNBUDGETED:$a"
                    else -> UNBUDGETED
                }
            }
        }
        for (t in journal.transactions) {
            if (!t.date.isBefore(end)) break
            val col = column(t.date)
            if (col < 0 && !opts.historical) continue
            for (p in t.postings) {
                val acct = remap(p.account)
                if (!query.matches(t, p.copy(account = acct), originalAccount = p.account)) continue
                val nd = node(clipAccount(acct, depth))
                nd.add(actual = true, col, p.reportAmount(opts.atCost))
            }
        }
        val goalQuery = query.goalQuery()
        for (t in goalTxns) {
            if (!t.date.isBefore(end)) continue
            val col = column(t.date)
            if (col < 0 && !opts.historical) continue
            for (p in t.postings) {
                if (!goalQuery.matches(t, p)) continue
                node(clipAccount(p.account, depth)).add(actual = false, col, p.reportAmount(opts.atCost))
            }
        }

        root.finish(opts.historical)
        markBoring(root, isRoot = true, opts.showUnbudgeted, opts.tree, journal.styles)
        sortTree(root, journal)

        val sign = if (opts.invert) -1 else 1
        val rows = ArrayList<BudgetRow>()
        fun mk(nd: Node, isRoot: Boolean, d: Int, boringParents: Int) {
            when {
                nd.allBoring() -> return
                nd.boring && isRoot -> nd.children.forEach { mk(it, false, d, 0) }
                nd.boring -> nd.children.forEach { mk(it, false, d, boringParents + 1) }
                else -> {
                    val display =
                        if (opts.tree) nd.name.split(':').takeLast(boringParents + 1).joinToString(":") else nd.name
                    rows.add(BudgetRow(nd.name, display, if (opts.tree) d else 0, nd.cells(sign)))
                    nd.children.forEach { mk(it, false, d + 1, 0) }
                }
            }
        }
        mk(root, true, 0, 0)
        val totals = (0 until n).map { i ->
            BudgetCell(root.actualIncl[i] * sign, if (root.inGoal) root.goalIncl[i] * sign else null)
        }
        return BudgetReport(periods, rows, totals, rules.size)
    }

    /**
     * The actual amount as a whole percentage of the goal, or null if they aren't the same single
     * commodity or the goal is zero (hledger's rule for showing a percentage).
     */
    fun percent(cell: BudgetCell, styles: Styles): Int? {
        val goal = cell.goal ?: return null
        if (goal.parts.size != 1) return null
        val (gc, gq) = goal.parts.entries.first()
        if (MixedAmount.of(gc, gq).looksZero(styles)) return null
        val actual = cell.actual
        val aq = when {
            actual.looksZero(styles) -> BigDecimal.ZERO
            actual.parts.size == 1 && actual.parts.containsKey(gc) -> actual.parts.getValue(gc)
            else -> return null
        }
        return aq.multiply(BigDecimal(100)).divide(gq, 0, RoundingMode.HALF_EVEN).toInt()
    }

    // ------------------------------------------------------------------ internals

    private class Node(val name: String, n: Int) {
        val children = ArrayList<Node>()

        // own changes per period, and before the first period (for -H)
        val actualOwn = Array(n) { MixedAmount.ZERO }
        val goalOwn = Array(n) { MixedAmount.ZERO }
        var actualBefore = MixedAmount.ZERO
        var goalBefore = MixedAmount.ZERO
        var inActual = false
        var inGoal = false
        var actualPostings = 0
        var goalPostings = 0
        var actualIncl = Array(n) { MixedAmount.ZERO }
        var goalIncl = Array(n) { MixedAmount.ZERO }
        var boringActual = true
        var boringGoal = true
        var boring = true

        fun add(actual: Boolean, col: Int, amt: MixedAmount) {
            if (actual) {
                inActual = true
                actualPostings++
                if (col < 0) actualBefore += amt else actualOwn[col] = actualOwn[col] + amt
            } else {
                inGoal = true
                goalPostings++
                if (col < 0) goalBefore += amt else goalOwn[col] = goalOwn[col] + amt
            }
        }

        /** Accumulate (-H), then compute inclusive amounts and tree membership, bottom up. */
        fun finish(historical: Boolean) {
            children.forEach { it.finish(historical) }
            if (historical) {
                var a = actualBefore
                var g = goalBefore
                for (i in actualOwn.indices) {
                    a += actualOwn[i]; actualOwn[i] = a
                    g += goalOwn[i]; goalOwn[i] = g
                }
            }
            inActual = inActual || children.any { it.inActual }
            inGoal = inGoal || children.any { it.inGoal }
            actualIncl =
                Array(actualOwn.size) { i -> actualOwn[i] + MixedAmount.sum(children.map { it.actualIncl[i] }) }
            goalIncl = Array(goalOwn.size) { i -> goalOwn[i] + MixedAmount.sum(children.map { it.goalIncl[i] }) }
        }

        fun allBoring(): Boolean = boring && children.all { it.allBoring() }

        fun cells(sign: Int): List<BudgetCell> = actualIncl.indices.map { i ->
            BudgetCell(
                if (inActual) actualIncl[i] * sign else MixedAmount.ZERO,
                if (inGoal) goalIncl[i] * sign else null,
            )
        }
    }

    /** hledger's markAccountBoring, done separately for the actual and goal trees, then combined. */
    private fun markBoring(nd: Node, isRoot: Boolean, showEmpty: Boolean, tree: Boolean, styles: Styles) {
        nd.children.forEach { markBoring(it, false, showEmpty, tree, styles) }
        if (isRoot) {
            nd.boringActual = true; nd.boringGoal = true; nd.boring = true; return
        }
        nd.boringActual = !nd.inActual || boringIn(nd, showEmpty, tree, styles, actual = true)
        nd.boringGoal = !nd.inGoal || boringIn(nd, showEmpty, tree, styles, actual = false)
        nd.boring = nd.boringActual && nd.boringGoal
    }

    private fun boringIn(nd: Node, showEmpty: Boolean, tree: Boolean, styles: Styles, actual: Boolean): Boolean {
        val own = if (actual) nd.actualOwn else nd.goalOwn
        val zero = own.all { it.looksZero(styles) }
        if (!tree) {
            // List mode: an account is shown if it has amounts of its own (or, with -E, any postings of its own).
            val hasPostings = (if (actual) nd.actualPostings else nd.goalPostings) > 0
            return zero && !(showEmpty && hasPostings)
        }
        val subs = nd.children.filter { if (actual) it.inActual else it.inGoal }
        val keepEmpty = showEmpty && subs.isEmpty()
        val interesting = subs.count { interestingIn(it, actual) }
        return zero && !keepEmpty && interesting < 2
    }

    private fun interestingIn(nd: Node, actual: Boolean): Boolean {
        val inTree = if (actual) nd.inActual else nd.inGoal
        if (!inTree) return false
        val boring = if (actual) nd.boringActual else nd.boringGoal
        return !boring || nd.children.any { interestingIn(it, actual) }
    }

    private fun sortTree(nd: Node, journal: Journal) {
        nd.children.sortWith(compareBy<Node>({ journal.declarationIndex(it.name) ?: Int.MAX_VALUE }, { it.name }))
        nd.children.forEach { sortTree(it, journal) }
    }
}
