package app.ledgedroid.core

import java.time.LocalDate
import java.time.Month
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The person's own way of reading shared text (Settings › Share patterns): a regular expression whose
 * named groups pick things out, (?<amount>…), (?<currency>…), (?<payee>…), (?<date>…), (?<account>…),
 * any of them. It ignores case. With [templateId], a match starts from that template.
 */
data class SharePattern(
    val id: String,
    val name: String,
    val regex: String,
    val templateId: String? = null,
    val enabled: Boolean = true,
) {
    /** What's wrong with [regex], or null. */
    val problem: String?
        get() = try {
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
            if (GROUPS.none { regex.contains("(?<$it>") }) "Name at least one group, eg (?<amount>[0-9.,]+)" else null
        } catch (e: PatternSyntaxException) {
            e.description + (if (e.index >= 0) " (at ${e.index + 1})" else "")
        }

    companion object {
        val GROUPS = listOf("amount", "currency", "payee", "date", "account")
    }
}

/** What was picked out of shared text, and by what ([matchedBy]: a pattern's name, or null for built-in). */
data class SharedEntry(
    val text: String,
    val date: LocalDate? = null,
    val payee: String? = null,
    val amount: String? = null,
    val commodity: String? = null,
    val account: String? = null,
    val templateId: String? = null,
    val matchedBy: String? = null,
) {
    val found: Boolean get() = date != null || payee != null || amount != null || account != null || templateId != null

    /** As quick add's defaults; the amount with its currency, eg $4.50. */
    fun hints(): QuickHints = QuickHints(date, payee, account, amount?.let { a ->
        val c = commodity.orEmpty()
        if (c.isEmpty()) a else if (c.length == 1 && !c[0].isLetter()) "$c$a" else "$a $c"
    })
}

/**
 * Reading a bank SMS, a notification's text or a note shared to Ledgedroid: the person's patterns first,
 * in order, then built-in ones that know common wording ("spent $4.50 at Corner Cafe on 02/11").
 */
object ShareParse {
    private val SYMBOLS = mapOf("$" to "USD", "€" to "EUR", "£" to "GBP", "¥" to "JPY", "₹" to "INR", "₩" to "KRW", "₺" to "TRY", "₽" to "RUB")
    private val CODE = "[A-Z]{3}"
    private const val NUM = "\\d[\\d,.' ]*\\d|\\d"
    private val AMOUNT_BEFORE = Pattern.compile("(?<cur>[$€£¥₹₩₺₽]|\\b$CODE\\b|\\bRs\\.?)\\s?(?<num>$NUM)")
    private val AMOUNT_AFTER = Pattern.compile("(?<num>$NUM)\\s?(?<cur>[$€£¥₹₩₺₽]|\\b$CODE\\b)")
    private val KEYWORD = Pattern.compile("(spent|paid|debited|charged|purchase|payment|withdrawn|sent|transfer(red)?)\\b", Pattern.CASE_INSENSITIVE)
    private val PAYEE = Pattern.compile(
        "\\b(?:at|to|from|@)\\s+(?<payee>[\\p{L}\\p{N}][\\p{L}\\p{N}&'.\\- ]{1,40}?)(?=\\s+(?:on|for|via|using|ref|with|card|ending|at)\\b|\\s*[,;:\\n]|\\.(?:\\s|$)|$)",
        Pattern.CASE_INSENSITIVE,
    )
    private val ISO = Pattern.compile("\\b(?<y>\\d{4})[-/.](?<m>\\d{1,2})[-/.](?<d>\\d{1,2})\\b")
    private val NUMERIC = Pattern.compile("\\b(?<a>\\d{1,2})[-/.](?<b>\\d{1,2})(?:[-/.](?<y>\\d{2,4}))?\\b")
    private val NAMED = Pattern.compile(
        "\\b(?:(?<d1>\\d{1,2})(?:st|nd|rd|th)?[ -]?(?<m1>[A-Za-z]{3,9})|(?<m2>[A-Za-z]{3,9})[ -]?(?<d2>\\d{1,2})(?:st|nd|rd|th)?)(?:,?[ -]?(?<y>\\d{4}|\\d{2}))?\\b",
    )

    fun parse(text: String, patterns: List<SharePattern>, today: LocalDate, commodities: Collection<String> = emptyList()): SharedEntry {
        for (p in patterns) {
            if (!p.enabled || p.problem != null) continue
            val m = Pattern.compile(p.regex, Pattern.CASE_INSENSITIVE).matcher(text)
            if (!m.find()) continue
            val cur = group(m, "currency")
            return SharedEntry(
                text = text,
                date = group(m, "date")?.let { parseDate(it, today) },
                payee = group(m, "payee")?.let(::tidy),
                amount = group(m, "amount")?.let { number(it) },
                commodity = cur?.let { commodity(it, commodities) } ?: group(m, "amount")?.let { inlineCurrency(it, commodities) },
                account = group(m, "account")?.trim(),
                templateId = p.templateId,
                matchedBy = p.name,
            )
        }
        return builtIn(text, today, commodities)
    }

    /** The built-in reading: the amount (preferring one after "spent", "paid"…), the payee after at/to, a date. */
    fun builtIn(text: String, today: LocalDate, commodities: Collection<String> = emptyList()): SharedEntry {
        val amounts = (all(AMOUNT_BEFORE, text) + all(AMOUNT_AFTER, text)).sortedBy { it.first }
        val keyword = KEYWORD.matcher(text).let { if (it.find()) it.end() else -1 }
        val chosen = amounts.firstOrNull { keyword >= 0 && it.first >= keyword } ?: amounts.firstOrNull()
        val payee = PAYEE.matcher(text).let { m ->
            var found: String? = null
            while (m.find()) {
                val p = tidy(m.group("payee"))
                if (p.any { it.isLetter() } && commodity(p, emptyList()) == null) { found = p; break }
            }
            found
        }
        return SharedEntry(
            text = text,
            date = findDate(text, today),
            payee = payee,
            amount = chosen?.second?.let { number(it.second) },
            commodity = chosen?.second?.let { commodity(it.first, commodities) },
        )
    }

    /** The first date in [text] in a form shared messages use. */
    fun findDate(text: String, today: LocalDate): LocalDate? {
        ISO.matcher(text).let { m -> if (m.find()) dateOf(m.group("y").toInt(), m.group("m").toInt(), m.group("d").toInt())?.let { return it } }
        NAMED.matcher(text).let { m ->
            while (m.find()) {
                val month = monthOf(m.group("m1") ?: m.group("m2")) ?: continue
                val day = (m.group("d1") ?: m.group("d2")).toInt()
                return withYear(m.group("y"), today) { y -> dateOf(y, month, day) } ?: continue
            }
        }
        NUMERIC.matcher(text).let { m ->
            while (m.find()) {
                val a = m.group("a").toInt(); val b = m.group("b").toInt()
                // Day first, as most banks outside the US write it, unless that can't be a date.
                val (day, month) = if (a > 12 || b <= 12) a to b else b to a
                withYear(m.group("y"), today) { y -> dateOf(y, month, day) }?.let { return it }
            }
        }
        return null
    }

    /** A date picked out by a pattern's date group: any form [findDate] or quick add understands. */
    fun parseDate(s: String, today: LocalDate): LocalDate? = QuickAdd.parseDate(s, today) ?: findDate(s, today)

    private fun withYear(y: String?, today: LocalDate, make: (Int) -> LocalDate?): LocalDate? {
        if (y != null) return make(if (y.length == 2) 2000 + y.toInt() else y.toInt())
        // No year: this year, or last year if that would be more than a day ahead.
        val d = make(today.year) ?: return null
        return if (d.isAfter(today.plusDays(1))) make(today.year - 1) else d
    }

    private fun dateOf(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (e: java.time.DateTimeException) { null }

    private fun monthOf(s: String): Int? {
        val t = s.lowercase(Locale.ROOT)
        if (t.length < 3) return null
        return Month.entries.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(t) }?.value
    }

    private fun all(p: Pattern, text: String): List<Pair<Int, Pair<String, String>>> {
        val m = p.matcher(text)
        val out = ArrayList<Pair<Int, Pair<String, String>>>()
        while (m.find()) out.add(m.start() to (m.group("cur") to m.group("num")))
        return out
    }

    private fun group(m: Matcher, name: String): String? = try {
        m.group(name)?.takeIf { it.isNotBlank() }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** A number as typed in quick add: digit groups removed, a decimal comma made a point when it's one. */
    fun number(s: String): String {
        val t = s.trim().replace(" ", "").replace("'", "").replace("\u00A0", "")
        val digitsOnly = t.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
        val lastDot = digitsOnly.lastIndexOf('.'); val lastComma = digitsOnly.lastIndexOf(',')
        return when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) digitsOnly.replace(",", "") else digitsOnly.replace(".", "").replace(',', '.')
            lastComma >= 0 && digitsOnly.length - lastComma - 1 in 1..2 && digitsOnly.count { it == ',' } == 1 -> digitsOnly.replace(',', '.')
            lastComma >= 0 -> digitsOnly.replace(",", "")
            else -> digitsOnly
        }
    }

    /** The journal's own commodity for a currency as written: $ or USD becomes whichever the journal uses. */
    fun commodity(written: String, commodities: Collection<String>): String? {
        val w = written.trim().removeSuffix(".")
        val code = when {
            w.equals("Rs", ignoreCase = true) -> "INR"
            w in SYMBOLS -> SYMBOLS.getValue(w)
            w.length == 3 && w.all { it.isUpperCase() } -> w
            else -> return null
        }
        val symbol = SYMBOLS.entries.firstOrNull { it.value == code }?.key
        return when {
            w in commodities -> w
            code in commodities -> code
            symbol != null && symbol in commodities -> symbol
            else -> w.takeIf { it in SYMBOLS } ?: code
        }
    }

    /** A currency written inside an amount group, eg $4.50 or 4.50 EUR. */
    private fun inlineCurrency(amount: String, commodities: Collection<String>): String? {
        val split = QuickAdd.splitAmount(amount) ?: return null
        return split.second?.let { commodity(it, commodities) }
    }

    private fun tidy(s: String) = s.trim().trimEnd('.', ',', '-').replace(Regex("\\s+"), " ")
}
