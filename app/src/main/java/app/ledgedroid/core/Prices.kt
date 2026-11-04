package app.ledgedroid.core

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/** A market price from a P directive: one unit of [commodity] was worth [price] on [date]. */
data class MarketPrice(val date: LocalDate, val commodity: String, val price: Amount, val line: Int = 0)

/** What to value amounts in: -X COMMODITY, or with null -V's default valuation commodity for each. */
data class ValueTarget(val commodity: String?) {
    fun describe(): String = if (commodity == null) "market value" else "value in $commodity"
}

/**
 * Market valuation like hledger's -V and -X, from the journal's P prices. To value commodity A in B on a
 * date it looks, in this order (hledger's "Finding market price"):
 *  1. A's latest price in B on or before the date,
 *  2. the inverse of B's latest price in A,
 *  3. the shortest chain of such prices (forward ones first, then forward and inverse),
 * and with -V, B is the commodity of A's latest price on or before the date. Amounts with no price
 * found are left as they are. A chain is only used when every link is dated on or before the date.
 */
class Valuation(prices: List<MarketPrice>, val target: ValueTarget) {
    private val sorted = prices.sortedWith(compareBy<MarketPrice>({ it.date }, { it.line }))
    private val edgesCache = HashMap<LocalDate, Map<String, Map<String, BigDecimal>>>()
    private val rateCache = HashMap<Pair<String, LocalDate>, Pair<String, BigDecimal>?>()

    val hasPrices: Boolean get() = sorted.isNotEmpty()

    /** [amount] valued on [date]. */
    fun value(amount: MixedAmount, date: LocalDate): MixedAmount {
        if (amount.parts.isEmpty()) return amount
        return MixedAmount.sum(amount.parts.map { (c, q) ->
            val r = rate(c, date)
            if (r == null) MixedAmount.of(c, q) else MixedAmount.of(r.first, q.multiply(r.second, MC))
        })
    }

    /** The commodity [commodity] is valued in on [date], and the rate, or null to leave it. */
    fun rate(commodity: String, date: LocalDate): Pair<String, BigDecimal>? = rateCache.getOrPut(commodity to date) {
        val forward = edges(date)
        val to = target.commodity ?: defaultFor(commodity, date) ?: return@getOrPut null
        if (to == commodity) return@getOrPut null
        forward[commodity]?.get(to)?.let { return@getOrPut to to it }
        forward[to]?.get(commodity)?.let { return@getOrPut to to BigDecimal.ONE.divide(it, MC) }
        val both = HashMap<String, MutableMap<String, BigDecimal>>()
        for ((from, tos) in forward) for ((t, r) in tos) {
            both.getOrPut(from) { HashMap() }.putIfAbsent(t, r)
            both.getOrPut(t) { HashMap() }.putIfAbsent(from, BigDecimal.ONE.divide(r, MC))
        }
        (chain(forward, commodity, to) ?: chain(both, commodity, to))?.let { to to it }
    }

    /** -V's default valuation commodity: that of [commodity]'s latest price on or before [date]. */
    private fun defaultFor(commodity: String, date: LocalDate): String? =
        sorted.lastOrNull { it.commodity == commodity && !it.date.isAfter(date) }?.price?.commodity

    /** Each commodity's latest price in each other commodity, on or before [date]. */
    private fun edges(date: LocalDate): Map<String, Map<String, BigDecimal>> = edgesCache.getOrPut(date) {
        val m = HashMap<String, HashMap<String, BigDecimal>>()
        for (p in sorted) {
            if (p.date.isAfter(date)) break
            if (p.commodity == p.price.commodity || p.price.quantity.signum() == 0) continue
            m.getOrPut(p.commodity) { HashMap() }[p.price.commodity] = p.price.quantity // later ones win
        }
        m
    }

    /** The product of rates along the shortest path from [from] to [to], breadth first. */
    private fun chain(graph: Map<String, Map<String, BigDecimal>>, from: String, to: String): BigDecimal? {
        val rateTo = HashMap<String, BigDecimal>().apply { put(from, BigDecimal.ONE) }
        val queue = ArrayDeque<String>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for ((next, r) in graph[c].orEmpty().entries.sortedBy { it.key }) {
                if (next in rateTo) continue
                rateTo[next] = rateTo.getValue(c).multiply(r, MC)
                if (next == to) return rateTo[next]
                queue.add(next)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- reports, valued

    fun balance(r: BalanceReport, date: LocalDate) =
        BalanceReport(r.rows.map { it.copy(amount = value(it.amount, date)) }, value(r.total, date))

    fun compound(r: CompoundReport, date: LocalDate) = r.copy(
        sections = r.sections.map { s -> s.copy(report = balance(s.report, date)) },
        net = value(r.net, date),
    )

    /** Each column valued on its last day; totals and averages added up again from the valued columns. */
    fun multiPeriod(r: MultiPeriodReport): MultiPeriodReport =
        r.copy(rows = r.rows.map { row(it, r.periods) }, totals = row(r.totals, r.periods))

    fun multiCompound(r: MultiPeriodCompound): MultiPeriodCompound = r.copy(
        sections = r.sections.map { it.copy(report = multiPeriod(it.report)) },
        net = row(r.net, r.periods),
    )

    fun budget(r: BudgetReport): BudgetReport {
        fun cells(cs: List<BudgetCell>) = cs.mapIndexed { i, c ->
            val d = lastDay(r.periods[i])
            BudgetCell(value(c.actual, d), c.goal?.let { value(it, d) })
        }
        return r.copy(rows = r.rows.map { it.copy(cells = cells(it.cells)) }, totals = cells(r.totals))
    }

    /** A register valued on one date (the report's end), like hledger register -V. */
    fun register(r: RegisterReport, date: LocalDate) = r.copy(
        opening = r.opening?.let { value(it, date) },
        rows = r.rows.map { it.copy(amount = value(it.amount, date), runningTotal = value(it.runningTotal, date)) },
        total = value(r.total, date),
    )

    private fun row(row: MultiPeriodRow, periods: List<DateSpan>): MultiPeriodRow {
        val cells = row.cells.mapIndexed { i, c -> value(c, lastDay(periods[i])) }
        val total = row.total?.let { MixedAmount.sum(cells) }
        return row.copy(cells = cells, total = total, average = total?.let { MultiPeriodReports.averageOf(it, cells.size) })
    }

    private fun lastDay(p: DateSpan): LocalDate = p.end!!.minusDays(1)

    companion object {
        private val MC = MathContext.DECIMAL64

        /**
         * The valuation date of a single-period report, like hledger: the day before an explicit end, else
         * the latest transaction date or P date that isn't in the future.
         */
        fun reportDate(journal: Journal, span: DateSpan, today: LocalDate): LocalDate =
            span.end?.minusDays(1) ?: listOfNotNull(
                journal.lastDate,
                journal.prices.map { it.date }.filter { !it.isAfter(today) }.maxOrNull(),
            ).maxOrNull() ?: today
    }
}
