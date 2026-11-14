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
    /**
     * The messages it reads are money in (a refund, a salary credited): the amount goes in negative, so the
     * first account (eg income:salary) gives and the second (the bank) receives. A credit group flips this.
     */
    val moneyIn: Boolean = false,
) {
    /** What's wrong with [regex], or null. */
    val problem: String?
        get() = try {
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE)
            if (GROUPS.none { regex.contains("(?<$it>") } && tagGroups(regex).isEmpty()) "Name at least one group, eg (?<amount>[0-9.,]+)" else null
        } catch (e: PatternSyntaxException) {
            e.description + (if (e.index >= 0) " (at ${e.index + 1})" else "")
        }

    companion object {
        /**
         * The groups a pattern can name: what goes in each part of the transaction. Besides these, a group named
         * tag and a name, like (?<tagCard>[0-9]{4}), becomes the tag card:1234.
         */
        val GROUPS = listOf("amount", "currency", "payee", "date", "account", "account2", "note", "comment", "tags", "code", "status", "credit")

        /** The tag groups in [regex], as (group name, tag name): tagCard → card. */
        fun tagGroups(regex: String): List<Pair<String, String>> =
            Regex("\\(\\?<(tag[A-Z][A-Za-z0-9]*)>").findAll(regex).map { m ->
                val g = m.groupValues[1]
                g to g.removePrefix("tag").replaceFirstChar { it.lowercase() }
            }.distinct().toList()
    }
}

/** Which account a lookup table fills. */
enum class LookupTarget(val label: String) {
    ACCOUNT("Account 1"),
    ACCOUNT2("Account 2"),
}

/** One row of a lookup table: a captured [value] (eg a card's last four digits) and the [account] it means. */
data class LookupEntry(val value: String, val account: String)

/**
 * A lookup table (Settings › Share patterns): the value a pattern's [group] captured (any named group, eg
 * (?<card>[0-9]{4}) or a tag group like tagCard) is looked up in [entries], and the account found fills
 * [target]. One pattern can then serve every card at a bank, each with its own paying account. Values match
 * ignoring case and spaces, whole: a row 21 doesn't match the card 4821.
 */
data class LookupTable(
    val id: String,
    val name: String,
    val group: String,
    val target: LookupTarget = LookupTarget.ACCOUNT2,
    val entries: List<LookupEntry> = emptyList(),
    val enabled: Boolean = true,
) {
    /** The account for [value], or null. */
    fun find(value: String): String? {
        val v = key(value)
        return entries.firstOrNull { key(it.value) == v && it.account.isNotBlank() }?.account?.trim()
    }

    private fun key(s: String) = s.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)
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
    /** What goes after the payee's |. */
    val note: String? = null,
    /** A comment, and tags (card:1234), for the transaction's comment. */
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    /** The reference, written as hledger's code: (A1B2C3). */
    val code: String? = null,
    val status: Status? = null,
    /** The second account: where the money came from or went, eg assets:bank. */
    val account2: String? = null,
    /** Money in (a refund, credited): the first amount goes in negative. */
    val moneyIn: Boolean = false,
    /** Every named group's value, by group name, for lookup tables. */
    val captured: Map<String, String> = emptyMap(),
    /** The lookups that filled an account, to show: "card 4821 → liabilities:visa". */
    val lookedUp: List<String> = emptyList(),
) {
    val found: Boolean
        get() = date != null || payee != null || amount != null || account != null || templateId != null || note != null ||
                comment != null || tags.isNotEmpty() || code != null || status != null || account2 != null

    /** The comment and tags together, as they'd be written after ;. */
    val commentText: String? get() = listOfNotNull(comment, tags.joinToString(", ").ifEmpty { null }).joinToString(", ").ifEmpty { null }

    /** The amount as quick add takes it, with its currency and direction: $4.50, or $-4.50 for money in. */
    val signedAmount: String?
        get() = amount?.let { a ->
            val signed = if (!moneyIn) a else if (a.startsWith("-")) a.removePrefix("-") else "-$a"
            val c = commodity.orEmpty()
            if (c.isEmpty()) signed else if (c.length == 1 && !c[0].isLetter()) "$c$signed" else "$signed $c"
        }

    /**
     * As quick add's defaults. The status, code, note and comment go into the payee's default the way they're
     * typed there ("* (A1B2C3) Corner Cafe | latte ; card:1234"), so quick add reads them as usual.
     */
    fun hints(): QuickHints {
        val before = QuickAdd.markOf(status) + (code?.let { "($it) " } ?: "")
        val payeeText = listOfNotNull(
            (before + payee.orEmpty() + (note?.let { " | $it" } ?: "")).trim().ifEmpty { null },
            commentText?.let { "; $it" },
        ).joinToString(" ").ifEmpty { null }
        return QuickHints(date, payeeText, account, signedAmount, account2)
    }
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
    private val CREDIT_WORDS = Pattern.compile("\\b(credited|refund(ed)?|received|deposit(ed)?|reversed|reversal|cashback)\\b", Pattern.CASE_INSENSITIVE)
    private val CARD = Pattern.compile("(?:card|a/c|account)\\b[^0-9\\n]{0,20}?(?:ending(?: in)?|x{2,}|\\*{2,})\\s*([0-9]{4})\\b", Pattern.CASE_INSENSITIVE)
    private val REFERENCE = Pattern.compile("\\b(?:ref(?:erence)?|txn|transaction id)\\.?\\s*(?:no\\.?|number|#|:)?\\s*([A-Z0-9][A-Z0-9-]{3,})\\b", Pattern.CASE_INSENSITIVE)
    private val ISO = Pattern.compile("\\b(?<y>\\d{4})[-/.](?<m>\\d{1,2})[-/.](?<d>\\d{1,2})\\b")
    private val NUMERIC = Pattern.compile("\\b(?<a>\\d{1,2})[-/.](?<b>\\d{1,2})(?:[-/.](?<y>\\d{2,4}))?\\b")
    private val NAMED = Pattern.compile(
        "\\b(?:(?<d1>\\d{1,2})(?:st|nd|rd|th)?[ -]?(?<m1>[A-Za-z]{3,9})|(?<m2>[A-Za-z]{3,9})[ -]?(?<d2>\\d{1,2})(?:st|nd|rd|th)?)(?:,?[ -]?(?<y>\\d{4}|\\d{2}))?\\b",
    )

    fun parse(
        text: String,
        patterns: List<SharePattern>,
        today: LocalDate,
        commodities: Collection<String> = emptyList(),
        /** Lookup tables to fill accounts from captured values (none when they're turned off). */
        lookups: List<LookupTable> = emptyList(),
    ): SharedEntry {
        for (p in patterns) {
            if (!p.enabled || p.problem != null) continue
            val m = Pattern.compile(p.regex, Pattern.CASE_INSENSITIVE).matcher(text)
            if (!m.find()) continue
            val cur = group(m, "currency")
            // A credit group that matched (CR, credited…) turns the pattern's direction round.
            val credited = group(m, "credit") != null
            return applyLookups(SharedEntry(
                text = text,
                date = group(m, "date")?.let { parseDate(it, today) },
                payee = group(m, "payee")?.let(::tidy),
                amount = group(m, "amount")?.let { number(it) },
                commodity = cur?.let { commodity(it, commodities) } ?: group(m, "amount")?.let { inlineCurrency(it, commodities) },
                account = group(m, "account")?.trim(),
                templateId = p.templateId,
                matchedBy = p.name,
                note = group(m, "note")?.let(::tidy),
                comment = group(m, "comment")?.trim(),
                tags = listOfNotNull(group(m, "tags")?.trim()) +
                        SharePattern.tagGroups(p.regex).mapNotNull { (g, tag) -> group(m, g)?.trim()?.let { "$tag:$it" } },
                code = group(m, "code")?.trim()?.replace(")", ""),
                status = group(m, "status")?.let(::statusOf),
                account2 = group(m, "account2")?.trim(),
                moneyIn = p.moneyIn != credited,
                captured = groupNames(p.regex).mapNotNull { g -> group(m, g)?.trim()?.let { g to it } }.toMap(),
            ), lookups)
        }
        return applyLookups(builtIn(text, today, commodities), lookups)
    }

    /** The named groups in [regex]: (?<card>…) gives card. */
    fun groupNames(regex: String): List<String> =
        Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").findAll(regex).map { it.groupValues[1] }.distinct().toList()

    /**
     * [e] with its accounts filled from the enabled [tables] whose group captured a value that's in them. A
     * looked-up account replaces what was captured for it (so a pattern can capture a card straight into
     * account2 and the table turns it into the account), and like anything captured, wins over a template's.
     */
    fun applyLookups(e: SharedEntry, tables: List<LookupTable>): SharedEntry {
        var out = e
        for (t in tables) {
            if (!t.enabled) continue
            val value = e.captured[t.group] ?: continue
            val account = t.find(value) ?: continue
            out = when (t.target) {
                LookupTarget.ACCOUNT -> out.copy(account = account)
                LookupTarget.ACCOUNT2 -> out.copy(account2 = account)
            }
            out = out.copy(lookedUp = out.lookedUp + "${t.group} $value → $account")
        }
        return out
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
        val card = CARD.matcher(text).let { if (it.find()) it.group(1) else null }
        // Money in when it says so (credited, refund…) and doesn't say it was spent.
        val moneyIn = CREDIT_WORDS.matcher(text).find() && !KEYWORD.matcher(text).find()
        return SharedEntry(
            text = text,
            date = findDate(text, today),
            payee = payee,
            amount = chosen?.second?.let { number(it.second) },
            commodity = chosen?.second?.let { commodity(it.first, commodities) },
            moneyIn = moneyIn,
            // A card's last four digits, and a reference number, are worth keeping; the digits also as the card
            // group, for a lookup table.
            tags = listOfNotNull(card?.let { "card:$it" }),
            code = REFERENCE.matcher(text).let { if (it.find()) it.group(1) else null },
            captured = listOfNotNull(card?.let { "card" to it }).toMap(),
        )
    }

    /** A status as a message or a pattern's status group says it: * or cleared/posted, ! or pending. */
    fun statusOf(s: String): Status? {
        val t = s.trim().lowercase(Locale.ROOT)
        return when {
            t.startsWith("*") || t in setOf("cleared", "posted", "completed", "settled") -> Status.CLEARED
            t.startsWith("!") || t in setOf("pending", "authorised", "authorized", "on hold") -> Status.PENDING
            else -> null
        }
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
