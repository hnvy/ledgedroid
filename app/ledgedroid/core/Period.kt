package app.ledgedroid.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** A date range; start is inclusive, end is exclusive, null means unbounded (like hledger's DateSpan). */
data class DateSpan(val start: LocalDate? = null, val end: LocalDate? = null) {
    fun contains(d: LocalDate) = (start == null || !d.isBefore(start)) && (end == null || d.isBefore(end))
    fun beforeEnd(d: LocalDate) = end == null || d.isBefore(end)
    val isOpen get() = start == null && end == null

    companion object {
        val ALL = DateSpan()
    }
}

enum class PeriodPreset(val label: String) {
    ALL("All dates"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_QUARTER("This quarter"),
    THIS_YEAR("This year"),
    LAST_YEAR("Last year"),
    CUSTOM("Custom dates");

    fun span(today: LocalDate): DateSpan = when (this) {
        ALL, CUSTOM -> DateSpan.ALL
        THIS_MONTH -> today.withDayOfMonth(1).let { DateSpan(it, it.plusMonths(1)) }
        LAST_MONTH -> today.withDayOfMonth(1).minusMonths(1).let { DateSpan(it, it.plusMonths(1)) }
        THIS_QUARTER -> today.withDayOfMonth(1).withMonth((today.monthValue - 1) / 3 * 3 + 1)
            .let { DateSpan(it, it.plusMonths(3)) }

        THIS_YEAR -> today.with(TemporalAdjusters.firstDayOfYear()).let { DateSpan(it, it.plusYears(1)) }
        LAST_YEAR -> today.with(TemporalAdjusters.firstDayOfYear()).minusYears(1).let { DateSpan(it, it.plusYears(1)) }
    }
}

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")

/** A short human description of a span, eg "March 2026", "2025", "1 Jan 2026 – 14 Feb 2026". */
fun describeSpan(span: DateSpan): String {
    val s = span.start
    val e = span.end
    return when {
        s == null && e == null -> "All dates"
        s == null -> "Until ${e!!.minusDays(1).format(DAY)}"
        e == null -> "From ${s.format(DAY)}"
        s.dayOfMonth == 1 && e == s.plusMonths(1) -> s.format(MONTH)
        s.dayOfYear == 1 && e == s.plusYears(1) -> s.year.toString()
        s.dayOfMonth == 1 && e == s.plusMonths(3) && (s.monthValue - 1) % 3 == 0 -> "Q${(s.monthValue - 1) / 3 + 1} ${s.year}"
        else -> "${s.format(DAY)} – ${e.minusDays(1).format(DAY)}"
    }
}

// ---------------------------------------------------------------------- intervals

/** A repeating interval, as in hledger report intervals and periodic transaction rules. */
sealed class Interval {
    /** The latest interval boundary on or before [d]. */
    abstract fun boundaryOnOrBefore(d: LocalDate): LocalDate

    /** The boundary following [d]. */
    abstract fun next(d: LocalDate): LocalDate

    /** Rough length, used to pick the longest of several intervals. */
    abstract val approxDays: Int

    data class Days(val n: Int) : Interval() {
        override fun boundaryOnOrBefore(d: LocalDate) = d
        override fun next(d: LocalDate): LocalDate = d.plusDays(n.toLong())
        override val approxDays get() = n
    }

    data class Weeks(val n: Int) : Interval() {
        override fun boundaryOnOrBefore(d: LocalDate): LocalDate =
            d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

        override fun next(d: LocalDate): LocalDate = d.plusWeeks(n.toLong())
        override val approxDays get() = 7 * n
    }

    data class Months(val n: Int) : Interval() {
        override fun boundaryOnOrBefore(d: LocalDate): LocalDate = d.withDayOfMonth(1)
        override fun next(d: LocalDate): LocalDate = d.plusMonths(n.toLong())
        override val approxDays get() = 30 * n
    }

    data class Quarters(val n: Int) : Interval() {
        override fun boundaryOnOrBefore(d: LocalDate): LocalDate =
            d.withDayOfMonth(1).withMonth((d.monthValue - 1) / 3 * 3 + 1)

        override fun next(d: LocalDate): LocalDate = d.plusMonths(3L * n)
        override val approxDays get() = 91 * n
    }

    data class Years(val n: Int) : Interval() {
        override fun boundaryOnOrBefore(d: LocalDate): LocalDate = d.withDayOfYear(1)
        override fun next(d: LocalDate): LocalDate = d.plusYears(n.toLong())
        override val approxDays get() = 365 * n
    }

    /** every Nth day of the month (clamped to short months). */
    data class DayOfMonth(val day: Int) : Interval() {
        private fun inMonth(m: LocalDate): LocalDate = m.withDayOfMonth(minOf(day, m.lengthOfMonth()))
        override fun boundaryOnOrBefore(d: LocalDate): LocalDate =
            inMonth(d).let { if (it.isAfter(d)) inMonth(d.withDayOfMonth(1).minusMonths(1)) else it }

        override fun next(d: LocalDate): LocalDate = inMonth(d.withDayOfMonth(1).plusMonths(1))
        override val approxDays get() = 30
    }

    /** every Nth day of the week (1 = Monday). */
    data class DayOfWeek(val dow: Int) : Interval() {
        override fun boundaryOnOrBefore(d: LocalDate): LocalDate =
            d.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.of(dow)))

        override fun next(d: LocalDate): LocalDate = d.plusWeeks(1)
        override val approxDays get() = 7
    }
}

/**
 * Split [start, end) into consecutive interval periods. With [adjust], the first period starts at the
 * interval boundary on or before start (like hledger's splitSpan with adjustment).
 */
fun splitSpan(interval: Interval, start: LocalDate, end: LocalDate, adjust: Boolean): List<DateSpan> {
    val out = ArrayList<DateSpan>()
    var s = if (adjust) interval.boundaryOnOrBefore(start) else start
    while (s.isBefore(end) && out.size < 100_000) {
        val e = interval.next(s)
        out.add(DateSpan(s, e))
        s = e
    }
    return out
}

/** The interval and date span of a period expression like "monthly from 2024-01 to 2024-07". */
data class PeriodExpr(val interval: Interval?, val span: DateSpan)

/**
 * Parse a period expression (a practical subset of hledger's):
 *   intervals: daily weekly monthly quarterly yearly annually biweekly fortnightly bimonthly,
 *              every day|week|month|quarter|year, every N days|weeks|months|quarters|years,
 *              every Nth day [of month], every Nth day of week, every monday..sunday
 *   dates:     YYYY, YYYY-MM, YYYY-MM-DD (- / or . separators), YYYYqN;
 *              in X, X, from/since X, to/until X, X to Y, X..Y, X - Y
 * Throws IllegalArgumentException with a readable message.
 */
fun parsePeriodExpr(text: String): PeriodExpr {
    val words =
        text.lowercase().replace("..", " to ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()

    fun fail(): Nothing = throw IllegalArgumentException("can't understand the period \"$text\"")
    var i = 0
    fun peek() = words.getOrNull(i)

    var interval: Interval? = when (peek()) {
        "daily" -> Interval.Days(1)
        "weekly" -> Interval.Weeks(1)
        "biweekly", "fortnightly" -> Interval.Weeks(2)
        "monthly" -> Interval.Months(1)
        "bimonthly" -> Interval.Months(2)
        "quarterly" -> Interval.Quarters(1)
        "yearly", "annually" -> Interval.Years(1)
        else -> null
    }
    if (interval != null) i++
    else if (peek() == "every") {
        i++
        val w = peek() ?: fail()
        val n = w.toIntOrNull()
        val ord = Regex("^(\\d+)(st|nd|rd|th)$").find(w)?.groupValues?.get(1)?.toInt()
        val weekday = WEEKDAYS.indexOfFirst { it == w || it.take(3) == w }
        when {
            n != null -> {
                i++
                interval = unitInterval(peek()?.removeSuffix("s") ?: fail(), n) ?: fail(); i++
            }

            ord != null -> {
                i++
                if (peek() != "day") fail()
                i++
                if (peek() == "of") {
                    i++
                    when (peek()) {
                        "week" -> {
                            if (ord !in 1..7) fail(); interval = Interval.DayOfWeek(ord)
                        }

                        "month" -> interval = Interval.DayOfMonth(ord)
                        else -> fail()
                    }
                    i++
                } else interval = Interval.DayOfMonth(ord)
            }

            weekday >= 0 -> {
                interval = Interval.DayOfWeek(weekday + 1); i++
            }

            else -> {
                interval = unitInterval(w, 1) ?: fail(); i++
            }
        }
    }

    // date range
    val rest = words.subList(i, words.size).filter { it != "in" }
    var start: LocalDate? = null
    var end: LocalDate? = null
    var j = 0
    fun date(k: Int): DateSpan = rest.getOrNull(k)?.let { periodDate(it) } ?: fail()
    when {
        rest.isEmpty() -> Unit
        rest[0] == "from" || rest[0] == "since" -> {
            start = date(1).start; j = 2
            if (rest.getOrNull(j) in setOf("to", "until", "-")) {
                end = date(j + 1).start; j += 2
            } else if (j < rest.size) {
                end = date(j).start; j++
            }
        }

        rest[0] == "to" || rest[0] == "until" -> {
            end = date(1).start; j = 2
        }

        else -> {
            val first = date(0)
            j = 1
            if (rest.getOrNull(1) in setOf("to", "until", "-")) {
                start = first.start; end = date(2).start; j = 3
            } else if (rest.size > 1) {
                start = first.start; end = date(1).start; j = 2
            } else {
                start = first.start; end = first.end
            }
        }
    }
    if (j < rest.size) fail()
    return PeriodExpr(interval, DateSpan(start, end))
}

private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

private fun unitInterval(unit: String, n: Int): Interval? = when (unit) {
    "day" -> Interval.Days(n)
    "week" -> Interval.Weeks(n)
    "month" -> Interval.Months(n)
    "quarter" -> Interval.Quarters(n)
    "year" -> Interval.Years(n)
    else -> null
}

/** A single date in a period expression, as the span it names: 2024 = the year, 2024-03 = the month, etc. */
internal fun periodDate(s: String): DateSpan? {
    Regex("^(\\d{4})q([1-4])$").find(s)?.let { m ->
        val st = LocalDate.of(m.groupValues[1].toInt(), (m.groupValues[2].toInt() - 1) * 3 + 1, 1)
        return DateSpan(st, st.plusMonths(3))
    }
    val parts = s.split('-', '/', '.')
    val nums = parts.map { it.toIntOrNull() ?: return null }
    return try {
        when (nums.size) {
            1 -> if (parts[0].length == 4) LocalDate.of(nums[0], 1, 1).let { DateSpan(it, it.plusYears(1)) } else null
            2 -> LocalDate.of(nums[0], nums[1], 1).let { DateSpan(it, it.plusMonths(1)) }
            3 -> LocalDate.of(nums[0], nums[1], nums[2]).let { DateSpan(it, it.plusDays(1)) }
            else -> null
        }
    } catch (e: java.time.DateTimeException) {
        null
    }
}
