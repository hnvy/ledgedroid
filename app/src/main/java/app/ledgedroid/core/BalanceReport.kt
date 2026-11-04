package app.ledgedroid.core

import java.time.LocalDate

enum class AccountListMode { TREE, FLAT }

/** CHANGE: sum of postings in the period (hledger's default). END_BALANCE: balance at period end (-H). */
enum class Accumulation { CHANGE, END_BALANCE }

data class ReportOptions(
    val mode: AccountListMode = AccountListMode.TREE,
    /** Maximum account depth to show (deeper accounts are summed into their ancestor), or null for all. */
    val depth: Int? = null,
    /** Show accounts with zero balance (-E). */
    val showEmpty: Boolean = false,
    /** Sort by amount, largest first (-S), instead of by declaration order and name. */
    val sortByAmount: Boolean = false,
    /** Flip the sign of all amounts (--invert). */
    val invert: Boolean = false,
    val accumulation: Accumulation = Accumulation.CHANGE,
    val span: DateSpan = DateSpan.ALL,
    /** Show amounts converted to their cost where a cost was recorded (-B). */
    val atCost: Boolean = false,
)

/** A posting's amount as reported: its own commodity, or its cost with -B. */
fun Posting.reportAmount(atCost: Boolean): MixedAmount =
    if (atCost) amount.balancingValue().let { (c, q) -> MixedAmount.of(c, q) } else amount.toMixed()

/** One report line. displayName is the leaf name in tree mode, possibly prefixed with elided parents ("bank:checking"). */
data class BalanceRow(
    val fullName: String,
    val displayName: String,
    val indent: Int,
    val amount: MixedAmount,
    /** True if this row is at the depth limit, so any subaccounts were summed into it. */
    val atDepthLimit: Boolean = false,
)

data class BalanceReport(val rows: List<BalanceRow>, val total: MixedAmount)

/**
 * One section of a compound report. [sign] is -1 for normally-negative sections (liabilities,
 * revenues), which are shown with flipped sign so they read as positive numbers.
 * [increasesNet] says whether the section's displayed total adds to or subtracts from the net.
 */
data class CompoundSection(
    val title: String,
    val types: Set<AccountType>,
    val sign: Int,
    val increasesNet: Boolean,
    val report: BalanceReport = BalanceReport(emptyList(), MixedAmount.ZERO),
)

data class CompoundReport(
    val title: String,
    val netLabel: String,
    val sections: List<CompoundSection>,
    val net: MixedAmount
)

enum class ReportKind(val label: String) {
    BALANCE("All accounts"),
    BALANCE_SHEET("Balance sheet"),
    INCOME_STATEMENT("Income statement"),
    BUDGET("Budget"),
}

object Reports {

    /**
     * hledger's balance report, single-period: sum the matched postings per account,
     * build the account tree, hide "boring" accounts (zero balance, or a parent with only
     * one interesting child, which gets merged into its child's name), and lay out rows.
     */
    fun balance(
        journal: Journal,
        query: Query,
        opts: ReportOptions,
        accountFilter: ((String) -> Boolean)? = null,
        sign: Int = 1,
    ): BalanceReport {
        val depth = listOfNotNull(opts.depth, query.depth).minOrNull()
        val root = Node("")
        val index = HashMap<String, Node>()

        fun node(name: String): Node = index.getOrPut(name) {
            val parent = parentAccount(name)?.let { node(it) } ?: root
            Node(name).also { parent.children.add(it) }
        }

        for (t in journal.transactions) {
            if (!inReport(t.date, opts)) continue
            for (p in t.postings) {
                if (accountFilter != null && !accountFilter(p.account)) continue
                if (!query.matches(t, p)) continue
                val n = node(clipAccount(p.account, depth))
                n.own = n.own + p.reportAmount(opts.atCost)
                n.postings++
            }
        }
        root.sumInclusive()
        markBoring(root, isRoot = true, opts, journal.styles)

        val displaySign = sign * (if (opts.invert) -1 else 1)
        // When sorting by amount, the biggest displayed amounts come first.
        sortTree(root, journal, opts.sortByAmount && opts.mode == AccountListMode.TREE, displaySign)

        var rows = buildRows(root, opts, depth, displaySign)
        if (opts.sortByAmount && opts.mode == AccountListMode.FLAT) {
            rows = rows.sortedWith(compareByDescending<BalanceRow> { it.amount.sortKey() }.thenBy { it.fullName })
        }
        return BalanceReport(rows, root.inclusive * displaySign)
    }

    /** Balance sheet (assets and liabilities, at period end) or income statement (revenues and expenses, change in period). */
    fun compound(journal: Journal, query: Query, opts: ReportOptions, kind: ReportKind): CompoundReport {
        val (title, netLabel, sections, accumulation) = compoundShape(kind)
        val sectionOpts = opts.copy(accumulation = accumulation)
        val filled = sections.map { s ->
            s.copy(report = balance(journal, query, sectionOpts, { journal.accountType(it) in s.types }, s.sign))
        }
        val net = MixedAmount.sum(filled.map { if (it.increasesNet) it.report.total else -it.report.total })
        return CompoundReport(title, netLabel, filled, net)
    }

    /** The title, net label, empty sections and accumulation of a balance sheet or income statement. */
    internal fun compoundShape(kind: ReportKind): CompoundShape = when (kind) {
        ReportKind.BALANCE_SHEET -> CompoundShape(
            "Balance sheet", "Net worth",
            listOf(
                CompoundSection("Assets", setOf(AccountType.ASSET, AccountType.CASH), sign = 1, increasesNet = true),
                CompoundSection("Liabilities", setOf(AccountType.LIABILITY), sign = -1, increasesNet = false),
            ),
            Accumulation.END_BALANCE,
        )

        ReportKind.INCOME_STATEMENT -> CompoundShape(
            "Income statement", "Net income",
            listOf(
                CompoundSection("Revenues", setOf(AccountType.REVENUE), sign = -1, increasesNet = true),
                CompoundSection("Expenses", setOf(AccountType.EXPENSE), sign = 1, increasesNet = false),
            ),
            Accumulation.CHANGE,
        )

        ReportKind.BALANCE, ReportKind.BUDGET -> error("not a compound report")
    }

    internal data class CompoundShape(
        val title: String,
        val netLabel: String,
        val sections: List<CompoundSection>,
        val accumulation: Accumulation,
    )

    /**
     * Postings of one account (and optionally its subaccounts), newest first, with running balance.
     * [amountSign] keeps only positive or negative amounts (as in the journal, like amt:>0 and amt:<0);
     * the report counts both kinds in the period whatever it keeps. [filter] is the list's advanced
     * filters; the report also says which currencies, other accounts and tags the account's postings in
     * the period have, before those filters, to choose from.
     */
    fun register(
        journal: Journal,
        query: Query,
        opts: ReportOptions,
        account: String,
        includeSubaccounts: Boolean,
        accountFilter: ((String) -> Boolean)? = null,
        sign: Int = 1,
        amountSign: AmountSign = AmountSign.ALL,
        filter: RegisterFilter = RegisterFilter.NONE,
    ): RegisterReport {
        val displaySign = sign * (if (opts.invert) -1 else 1)
        var opening = MixedAmount.ZERO
        var running = MixedAmount.ZERO
        var positives = 0
        var negatives = 0
        val commodities = LinkedHashSet<String>()
        val others = HashMap<String, Int>()
        val tagNames = HashMap<String, Int>()
        val rows = ArrayList<RegisterRow>()
        val start = opts.span.start
        for (t in journal.transactions) {
            if (!opts.span.beforeEnd(t.date)) break
            for (p in t.postings) {
                val inAccount = if (includeSubaccounts) isSameOrSubaccount(p.account, account) else p.account == account
                if (!inAccount) continue
                if (accountFilter != null && !accountFilter(p.account)) continue
                if (!query.matches(t, p)) continue
                val before = start != null && t.date.isBefore(start)
                if (!before) {
                    commodities.add(p.amount.commodity)
                    for (o in t.postings) if (o.account != p.account) others.merge(o.account, 1, Int::plus)
                    for ((name, _) in tagsIn(t.comment) + tagsIn(p.comment)) tagNames.merge(name, 1, Int::plus)
                }
                if (!filter.matches(t, p)) continue
                val q = p.amount.quantity
                if (!before) {
                    if (q.signum() > 0) positives++ else if (q.signum() < 0) negatives++
                }
                if (!amountSign.matches(q)) continue
                val amt = p.reportAmount(opts.atCost) * displaySign
                if (before) {
                    if (opts.accumulation == Accumulation.END_BALANCE) opening += amt
                    continue
                }
                running += amt
                rows.add(
                    RegisterRow(
                        date = t.date,
                        description = t.description,
                        account = p.account,
                        otherAccounts = t.postings.filter { it.account != p.account }.map { it.account }.distinct(),
                        amount = amt,
                        runningTotal = opening + running,
                        line = t.line,
                        status = if (p.status != Status.UNMARKED) p.status else t.status,
                        generated = p.generated,
                        transactionIndex = t.index,
                    )
                )
            }
        }
        return RegisterReport(
            opening = if (opts.accumulation == Accumulation.END_BALANCE && start != null) opening else null,
            rows = rows.asReversed().toList(),
            total = opening + running,
            positives = positives,
            negatives = negatives,
            commodities = commodities.sorted(),
            otherAccounts = others.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key },
            tagNames = tagNames.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key },
        )
    }

    // ------------------------------------------------------------------ internals

    private fun inReport(d: LocalDate, opts: ReportOptions) = when (opts.accumulation) {
        Accumulation.CHANGE -> opts.span.contains(d)
        Accumulation.END_BALANCE -> opts.span.beforeEnd(d)
    }

    private class Node(val name: String) {
        val children = ArrayList<Node>()
        var own: MixedAmount = MixedAmount.ZERO
        var inclusive: MixedAmount = MixedAmount.ZERO
        var postings = 0
        var boring = false

        fun sumInclusive(): MixedAmount {
            inclusive = own + MixedAmount.sum(children.map { it.sumInclusive() })
            return inclusive
        }

        fun anyInteresting(): Boolean = !boring || children.any { it.anyInteresting() }
        fun allBoring(): Boolean = boring && children.all { it.allBoring() }
    }

    /** hledger's markAccountBoring, for a single column. */
    private fun markBoring(n: Node, isRoot: Boolean, opts: ReportOptions, styles: Styles) {
        for (c in n.children) markBoring(c, false, opts, styles)
        if (isRoot) {
            n.boring = true; return
        }
        val tree = opts.mode == AccountListMode.TREE
        // In tree mode, an account's own balance is what matters here; its children are judged separately.
        val zero = n.own.looksZero(styles)
        val keepEmpty = opts.showEmpty && (if (tree) n.children.isEmpty() else n.postings > 0)
        val isBoring = zero && !keepEmpty
        val isBoringParent = if (tree) n.children.count { it.anyInteresting() } < 2 else true
        n.boring = isBoring && isBoringParent
    }

    private fun sortTree(n: Node, journal: Journal, byAmount: Boolean, amountSign: Int) {
        if (byAmount) {
            n.children.sortWith(compareByDescending<Node> { (it.inclusive * amountSign).sortKey() }.thenBy { it.name })
        } else {
            n.children.sortWith(
                compareBy<Node>({ journal.declarationIndex(it.name) ?: Int.MAX_VALUE }, { it.name })
            )
        }
        for (c in n.children) sortTree(c, journal, byAmount, amountSign)
    }

    /** hledger's buildReportRows. */
    private fun buildRows(root: Node, opts: ReportOptions, depth: Int?, displaySign: Int): List<BalanceRow> {
        val tree = opts.mode == AccountListMode.TREE
        val rows = ArrayList<BalanceRow>()

        fun mk(n: Node, isRoot: Boolean, d: Int, boringParents: Int) {
            when {
                n.allBoring() -> return
                n.boring && isRoot -> n.children.forEach { mk(it, false, d, 0) }
                n.boring -> n.children.forEach { mk(it, false, d, boringParents + 1) }
                else -> {
                    val parts = n.name.split(':')
                    val display = if (tree) parts.takeLast(boringParents + 1).joinToString(":") else n.name
                    val amount = (if (tree) n.inclusive else n.own) * displaySign
                    rows.add(
                        BalanceRow(
                            n.name,
                            display,
                            if (tree) d else 0,
                            amount,
                            atDepthLimit = depth != null && parts.size >= depth
                        )
                    )
                    n.children.forEach { mk(it, false, d + 1, 0) }
                }
            }
        }
        mk(root, true, 0, 0)
        return rows
    }
}

data class RegisterRow(
    val date: LocalDate,
    val description: String,
    val account: String,
    val otherAccounts: List<String>,
    val amount: MixedAmount,
    val runningTotal: MixedAmount,
    val line: Int,
    val status: Status,
    /** Added by an auto posting rule. */
    val generated: Boolean = false,
    /** The transaction's [Transaction.index], to open it. */
    val transactionIndex: Int = -1,
)

/**
 * Rows are newest first. opening is the balance before the period, shown when reporting end balances.
 * positives and negatives count the postings in the period with a positive or negative amount in the
 * journal, after the advanced filters but before any sign filter, for the In / Out (Charges /
 * Payments...) buttons.
 */
data class RegisterReport(
    val opening: MixedAmount?,
    val rows: List<RegisterRow>,
    val total: MixedAmount,
    val positives: Int = 0,
    val negatives: Int = 0,
    /** The currencies, other accounts (most used first) and tag names (most used first) in the period. */
    val commodities: List<String> = emptyList(),
    val otherAccounts: List<String> = emptyList(),
    val tagNames: List<String> = emptyList(),
)
