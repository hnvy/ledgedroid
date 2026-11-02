package app.ledgedroid.core

/**
 * One row of a multi-period balance report. total and average are over the periods (hledger's -T and
 * -A); they are null for end balances, where adding up the columns means nothing.
 */
data class MultiPeriodRow(
    val fullName: String,
    val displayName: String,
    val indent: Int,
    val cells: List<MixedAmount>,
    val total: MixedAmount?,
    val average: MixedAmount?,
    val atDepthLimit: Boolean = false,
)

data class MultiPeriodReport(
    val periods: List<DateSpan>,
    val rows: List<MultiPeriodRow>,
    /** The column totals, as a row. */
    val totals: MultiPeriodRow,
)

data class MultiPeriodSection(val section: CompoundSection, val report: MultiPeriodReport)

data class MultiPeriodCompound(
    val title: String,
    val netLabel: String,
    val periods: List<DateSpan>,
    val sections: List<MultiPeriodSection>,
    val net: MultiPeriodRow,
)

/** hledger's multi-period balance report (balance -M, bs -M, is -M), laid out like [Reports.balance]. */
object MultiPeriodReports {

    /** The report's columns: [span] split by [interval], with open ends filled from the journal. */
    fun periods(journal: Journal, span: DateSpan, interval: ReportInterval): List<DateSpan> {
        val start = span.start ?: journal.firstDate ?: return emptyList()
        val end = span.end ?: journal.lastDate?.plusDays(1) ?: return emptyList()
        if (!start.isBefore(end)) return emptyList()
        // Like hledger, columns start at an interval boundary unless a start date was given.
        return splitSpan(interval.interval, start, end, adjust = span.start == null)
    }

    fun balance(
        journal: Journal,
        query: Query,
        opts: ReportOptions,
        interval: ReportInterval,
        accountFilter: ((String) -> Boolean)? = null,
        sign: Int = 1,
        periods: List<DateSpan> = periods(journal, opts.span, interval),
    ): MultiPeriodReport {
        val n = periods.size
        val historical = opts.accumulation == Accumulation.END_BALANCE
        val depth = listOfNotNull(opts.depth, query.depth).minOrNull()
        val root = Node("", n)
        if (n == 0) return MultiPeriodReport(periods, emptyList(), row("", "Total", 0, emptyList(), historical, false))
        val start = periods.first().start!!
        val end = periods.last().end!!

        val index = HashMap<String, Node>()
        fun node(name: String): Node = index.getOrPut(name) {
            val parent = parentAccount(name)?.let { node(it) } ?: root
            Node(name, n).also { parent.children.add(it) }
        }

        for (t in journal.transactions) {
            if (!t.date.isBefore(end)) break
            val before = t.date.isBefore(start)
            if (before && !historical) continue
            val col = if (before) -1 else periods.binarySearch { p ->
                if (!p.end!!.isAfter(t.date)) -1 else if (p.start!!.isAfter(t.date)) 1 else 0
            }
            for (p in t.postings) {
                if (accountFilter != null && !accountFilter(p.account)) continue
                if (!query.matches(t, p)) continue
                val nd = node(clipAccount(p.account, depth))
                val amt = p.reportAmount(opts.atCost)
                if (col < 0) nd.before += amt else nd.own[col] = nd.own[col] + amt
                nd.postings++
            }
        }
        root.finish(historical)
        markBoring(root, isRoot = true, opts, journal.styles)

        val displaySign = sign * (if (opts.invert) -1 else 1)
        sortTree(root, journal, opts.sortByAmount && opts.mode == AccountListMode.TREE, displaySign, historical)

        val tree = opts.mode == AccountListMode.TREE
        val rows = ArrayList<MultiPeriodRow>()
        fun mk(nd: Node, isRoot: Boolean, d: Int, boringParents: Int) {
            when {
                nd.allBoring() -> return
                nd.boring && isRoot -> nd.children.forEach { mk(it, false, d, 0) }
                nd.boring -> nd.children.forEach { mk(it, false, d, boringParents + 1) }
                else -> {
                    val parts = nd.name.split(':')
                    val display = if (tree) parts.takeLast(boringParents + 1).joinToString(":") else nd.name
                    val cells = (if (tree) nd.inclusive else nd.own).map { it * displaySign }
                    rows.add(
                        row(
                            nd.name,
                            display,
                            if (tree) d else 0,
                            cells,
                            historical,
                            depth != null && parts.size >= depth
                        )
                    )
                    nd.children.forEach { mk(it, false, d + 1, 0) }
                }
            }
        }
        mk(root, true, 0, 0)
        val sorted = if (opts.sortByAmount && !tree) {
            rows.sortedWith(compareByDescending<MultiPeriodRow> {
                sortKey(
                    it.cells,
                    historical
                )
            }.thenBy { it.fullName })
        } else rows
        val totals = row("", "Total", 0, root.inclusive.map { it * displaySign }, historical, false)
        return MultiPeriodReport(periods, sorted, totals)
    }

    /** Balance sheet or income statement with a column per period. */
    fun compound(
        journal: Journal,
        query: Query,
        opts: ReportOptions,
        interval: ReportInterval,
        kind: ReportKind
    ): MultiPeriodCompound {
        val shape = Reports.compoundShape(kind)
        val accumulation = shape.accumulation
        val sectionOpts = opts.copy(accumulation = accumulation)
        val periods = periods(journal, opts.span, interval)
        val sections = shape.sections.map { s ->
            MultiPeriodSection(
                s,
                balance(journal, query, sectionOpts, interval, { journal.accountType(it) in s.types }, s.sign, periods)
            )
        }
        val historical = accumulation == Accumulation.END_BALANCE
        val netCells = periods.indices.map { i ->
            MixedAmount.sum(sections.map { if (it.section.increasesNet) it.report.totals.cells[i] else -it.report.totals.cells[i] })
        }
        val net = row(shape.netLabel, shape.netLabel, 0, netCells, historical, false)
        return MultiPeriodCompound(shape.title, shape.netLabel, periods, sections, net)
    }

    // ------------------------------------------------------------------ internals

    private fun row(
        name: String,
        display: String,
        indent: Int,
        cells: List<MixedAmount>,
        historical: Boolean,
        atLimit: Boolean
    ): MultiPeriodRow {
        val total = if (historical) null else MixedAmount.sum(cells)
        return MultiPeriodRow(name, display, indent, cells, total, total?.let { averageOf(it, cells.size) }, atLimit)
    }


    /** The average per period, at a few more decimal places than usual, rounded later for display. */
    private fun averageOf(total: MixedAmount, n: Int): MixedAmount {
        if (n == 0) return MixedAmount.ZERO
        val d = java.math.BigDecimal(n)
        return MixedAmount.of(total.parts.mapValues { (_, q) ->
            q.divide(
                d,
                maxOf(q.scale(), 0) + 4,
                java.math.RoundingMode.HALF_EVEN
            )
        })
    }

    private fun sortKey(cells: List<MixedAmount>, historical: Boolean) =
        if (historical) cells.lastOrNull()?.sortKey() ?: java.math.BigDecimal.ZERO
        else MixedAmount.sum(cells).sortKey()

    private class Node(val name: String, n: Int) {
        val children = ArrayList<Node>()
        var own = Array(n) { MixedAmount.ZERO }
        var before = MixedAmount.ZERO
        var inclusive = Array(n) { MixedAmount.ZERO }
        var postings = 0
        var boring = false

        /** Accumulate end balances (-H), then sum subaccounts, bottom up. */
        fun finish(historical: Boolean) {
            children.forEach { it.finish(historical) }
            if (historical) {
                var running = before
                for (i in own.indices) {
                    running += own[i]; own[i] = running
                }
            }
            inclusive = Array(own.size) { i -> own[i] + MixedAmount.sum(children.map { it.inclusive[i] }) }
        }

        fun anyInteresting(): Boolean = !boring || children.any { it.anyInteresting() }
        fun allBoring(): Boolean = boring && children.all { it.allBoring() }
    }

    /** As in [Reports.balance], but an account is only zero if it is zero in every column. */
    private fun markBoring(nd: Node, isRoot: Boolean, opts: ReportOptions, styles: Styles) {
        for (c in nd.children) markBoring(c, false, opts, styles)
        if (isRoot) {
            nd.boring = true; return
        }
        val tree = opts.mode == AccountListMode.TREE
        val zero = nd.own.all { it.looksZero(styles) }
        val keepEmpty = opts.showEmpty && (if (tree) nd.children.isEmpty() else nd.postings > 0)
        val isBoringParent = if (tree) nd.children.count { it.anyInteresting() } < 2 else true
        nd.boring = zero && !keepEmpty && isBoringParent
    }

    private fun sortTree(nd: Node, journal: Journal, byAmount: Boolean, sign: Int, historical: Boolean) {
        if (byAmount) {
            nd.children.sortWith(
                compareByDescending<Node> { sortKey(it.inclusive.map { a -> a * sign }, historical) }.thenBy { it.name }
            )
        } else {
            nd.children.sortWith(compareBy<Node>({ journal.declarationIndex(it.name) ?: Int.MAX_VALUE }, { it.name }))
        }
        for (c in nd.children) sortTree(c, journal, byAmount, sign, historical)
    }
}
