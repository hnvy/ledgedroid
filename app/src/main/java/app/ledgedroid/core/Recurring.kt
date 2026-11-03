package app.ledgedroid.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How often a template's transaction recurs: every [every] days, weeks or months, from [start]. */
data class Schedule(val every: Int, val unit: Unit, val start: LocalDate) {
    enum class Unit { DAYS, WEEKS, MONTHS }

    /** The [k]th occurrence. Months count from [start], so the 31st stays the 31st, or the month's last day. */
    fun occurrence(k: Long): LocalDate = when (unit) {
        Unit.DAYS -> start.plusDays(k * every)
        Unit.WEEKS -> start.plusWeeks(k * every)
        Unit.MONTHS -> start.plusMonths(k * every)
    }

    /** The latest occurrence on or before [date], or null if [date] is before [start]. */
    fun latestOnOrBefore(date: LocalDate): LocalDate? {
        if (date.isBefore(start) || every < 1) return null
        val units = when (unit) {
            Unit.DAYS -> ChronoUnit.DAYS.between(start, date)
            Unit.WEEKS -> ChronoUnit.WEEKS.between(start, date)
            Unit.MONTHS -> ChronoUnit.MONTHS.between(start, date)
        }
        var k = units / every
        while (occurrence(k).isAfter(date)) k--
        while (!occurrence(k + 1).isAfter(date)) k++
        return occurrence(k)
    }

    /** The first occurrence after [date]. */
    fun nextAfter(date: LocalDate): LocalDate {
        val latest = latestOnOrBefore(date) ?: return start
        var k = 0L
        while (!occurrence(k).isAfter(latest)) k++
        return occurrence(k)
    }

    fun describe(): String {
        val each = if (every == 1) "Every" else "Every $every"
        return when (unit) {
            Unit.DAYS -> if (every == 1) "Every day" else "$each days"
            Unit.WEEKS -> "$each ${if (every == 1) "week" else "weeks"} on ${start.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
            Unit.MONTHS -> "$each ${if (every == 1) "month" else "months"} on the ${ordinal(start.dayOfMonth)}"
        }
    }

    companion object {
        fun ordinal(n: Int): String = n.toString() + when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }

        fun weekly(day: DayOfWeek, from: LocalDate) = Schedule(1, Unit.WEEKS, from.with(java.time.temporal.TemporalAdjusters.nextOrSame(day)))
    }
}

/** A template whose transaction is due on [date]. */
data class Due(val template: Template, val date: LocalDate)

object Reminders {
    /**
     * Templates due by [today] that haven't been reminded about for that date yet ([reminded]: template id to
     * the due date last reminded), and that don't look recorded already: a transaction with the
     * template's payee from [early] days before the due date on. Only dates in the last [lookback] days,
     * so a long time away doesn't bring a pile of old reminders.
     */
    fun due(
        templates: List<Template>,
        journal: Journal?,
        today: LocalDate,
        reminded: Map<String, LocalDate>,
        lookback: Long = 7,
        early: Long = 3,
    ): List<Due> = templates.mapNotNull { t ->
        val s = t.schedule ?: return@mapNotNull null
        val d = s.latestOnOrBefore(today) ?: return@mapNotNull null
        if (ChronoUnit.DAYS.between(d, today) > lookback) return@mapNotNull null
        reminded[t.id]?.let { if (!it.isBefore(d)) return@mapNotNull null }
        if (journal != null && recorded(journal, t, d.minusDays(early), today)) return@mapNotNull null
        Due(t, d)
    }

    /** Whether there's a transaction with the template's payee between [from] and [to]. */
    fun recorded(journal: Journal, t: Template, from: LocalDate, to: LocalDate): Boolean {
        val payee = t.draft.payee.trim()
        if (payee.isEmpty()) return false
        return journal.transactions.any { tx ->
            !tx.forecast && !tx.date.isBefore(from) && !tx.date.isAfter(to) && Payees.of(tx.description).equals(payee, ignoreCase = true)
        }
    }
}

/** An account over (or near) its budget this period: [actual] spent of [goal], in [commodity]. */
data class BudgetAlert(val account: String, val commodity: String, val actual: BigDecimal, val goal: BigDecimal) {
    val percent: Int get() = actual.multiply(BigDecimal(100)).divide(goal, 0, RoundingMode.DOWN).toInt()
    val over: Boolean get() = actual > goal
}

object BudgetAlerts {
    /**
     * Budgeted accounts at [warnAt] percent of this month's budget or more (100: only those over it), from
     * the journal's periodic rules, like hledger balance --budget for this month. Goals are compared in
     * their own commodity; accounts with goals of zero or below (eg income) aren't checked.
     */
    fun check(journal: Journal, today: LocalDate, warnAt: Int = 100): List<BudgetAlert> {
        if (journal.periodicRules.isEmpty()) return emptyList()
        val start = today.withDayOfMonth(1)
        val report = Budgets.report(journal, Query.ALL, BudgetOptions(interval = ReportInterval.MONTHLY, span = DateSpan(start, start.plusMonths(1))))
        return report.rows.filter { !it.isUnbudgeted }.mapNotNull { row ->
            val cell = row.cells.firstOrNull() ?: return@mapNotNull null
            val goal = cell.goal?.takeIf { it.parts.size == 1 } ?: return@mapNotNull null
            val (c, g) = goal.parts.entries.single()
            if (g.signum() <= 0) return@mapNotNull null
            val actual = cell.actual.quantityOf(c)
            if (actual.multiply(BigDecimal(100)) < g.multiply(BigDecimal(warnAt))) null
            else BudgetAlert(row.fullName, c, actual, g)
        }.sortedByDescending { it.percent }
    }
}
