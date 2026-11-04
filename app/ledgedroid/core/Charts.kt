package app.ledgedroid.core

import java.math.BigDecimal
import java.time.LocalDate

/** A chart's numbers, in one [commodity], one per month in [periods]. */
data class LineChartData(val commodity: String, val periods: List<DateSpan>, val values: List<BigDecimal>)

/** Spending per month by category: each category's amount per month; the smaller ones lumped as Other. */
data class BarChartData(val commodity: String, val periods: List<DateSpan>, val categories: List<Pair<String, List<BigDecimal>>>) {
    fun total(i: Int): BigDecimal = categories.fold(BigDecimal.ZERO) { a, (_, v) -> a + v[i] }
}

object Charts {
    /** The months ending with [today]'s, [months] of them. */
    fun span(today: LocalDate, months: Int): DateSpan {
        val end = today.withDayOfMonth(1).plusMonths(1)
        return DateSpan(end.minusMonths(months.toLong()), end)
    }

    /** The commodity most postings are in: what charts are drawn in. */
    fun mainCommodity(journal: Journal): String =
        journal.transactions.asSequence().flatMap { it.postings }.groupingBy { it.amount.commodity }.eachCount()
            .maxByOrNull { it.value }?.key ?: ""

    /**
     * Net worth (assets less liabilities) at the end of each of the last [months] months, like hledger bs -M
     * net. Other commodities are converted with market prices when [valuation] is given, else left out.
     */
    fun netWorth(journal: Journal, today: LocalDate, months: Int, valuation: Valuation? = null, commodity: String = mainCommodity(journal)): LineChartData {
        var r = MultiPeriodReports.compound(journal, Query.ALL, ReportOptions(span = span(today, months)), ReportInterval.MONTHLY, ReportKind.BALANCE_SHEET)
        if (valuation != null) r = valuation.multiCompound(r)
        return LineChartData(commodity, r.periods, r.net.cells.map { it.quantityOf(commodity) })
    }

    /**
     * Spending per month for the last [months] months, by expense account at [depth] (eg expenses:food), like
     * hledger is -M --depth 2. The [top] biggest categories over the months are kept; the rest are Other.
     */
    fun spending(
        journal: Journal,
        today: LocalDate,
        months: Int,
        depth: Int = 2,
        top: Int = 6,
        valuation: Valuation? = null,
        commodity: String = mainCommodity(journal),
    ): BarChartData {
        var r = MultiPeriodReports.balance(
            journal, Query.ALL, ReportOptions(mode = AccountListMode.FLAT, depth = depth, span = span(today, months)),
            ReportInterval.MONTHLY, accountFilter = { journal.accountType(it) == AccountType.EXPENSE },
        )
        if (valuation != null) r = valuation.multiPeriod(r)
        val rows = r.rows.map { row -> row.fullName to row.cells.map { it.quantityOf(commodity) } }
            .filter { (_, v) -> v.any { it.signum() != 0 } }
            .sortedByDescending { (_, v) -> v.fold(BigDecimal.ZERO, BigDecimal::add) }
        val kept = rows.take(top)
        val rest = rows.drop(top)
        val other = if (rest.isEmpty()) emptyList() else listOf("Other" to r.periods.indices.map { i ->
            rest.fold(BigDecimal.ZERO) { a, (_, v) -> a + v[i] }
        })
        return BarChartData(commodity, r.periods, kept + other)
    }
}
