package app.ledgedroid.core

import java.math.BigDecimal
import java.time.LocalDate

/** One posting in the add-transaction form, as typed. A blank amount is calculated, like in hledger. */
data class DraftPosting(
    val account: String = "",
    val amount: String = "",
    val commodity: String = "",
    val comment: String = "",
) {
    val isBlank: Boolean get() = account.isBlank() && amount.isBlank()
}

/** A transaction being entered in the form. */
data class TransactionDraft(
    val date: LocalDate,
    val status: Status = Status.UNMARKED,
    val payee: String = "",
    val note: String = "",
    val comment: String = "",
    val postings: List<DraftPosting> = listOf(DraftPosting(), DraftPosting()),
) {
    /** hledger's description: "payee | note", or just the payee. */
    val description: String
        get() = if (note.isBlank()) payee.trim() else "${payee.trim()} | ${note.trim()}".trim()

    /** Indices of the postings that will be written: those with an account or an amount. */
    val used: List<Int> get() = postings.indices.filter { !postings[it].isBlank }
}

/**
 * How the form writes the space between each account name and its amount.
 *
 *  - Not [aligned]: [spaces] spaces (at least two, since hledger needs two spaces or a tab to tell where
 *    the account name ends).
 *  - [aligned]: the amounts' decimal marks line up at [column] (counting from 1), like hledger print's
 *    default layout (column 53). When an account name is too long for that, the column moves right for
 *    the whole transaction, keeping at least two spaces after the longest account.
 */
data class AmountLayout(
    val aligned: Boolean = false,
    val spaces: Int = DEFAULT_SPACES,
    val column: Int = DEFAULT_COLUMN,
    /**
     * Write amounts at the commodity's usual precision, padding whole ones ($4 as $4.00), as before. Off,
     * they're written with the decimals they have: as typed, and a calculated one like those it's worked
     * out from.
     */
    val padDecimals: Boolean = true,
) {
    /** The gap actually written when not aligned. */
    val gap: Int get() = spaces.coerceIn(MIN_SPACES, MAX_SPACES)

    companion object {
        const val MIN_SPACES = 2
        const val MAX_SPACES = 40
        const val DEFAULT_SPACES = 4
        const val MIN_COLUMN = 10
        const val MAX_COLUMN = 160

        /** hledger print's decimal mark column (Hledger.Data.Posting.defaultDecimalMarkColumn). */
        const val DEFAULT_COLUMN = 53
        val DEFAULT = AmountLayout()

        /** What the app starts with: amounts aligned on their decimal marks. */
        val APP_DEFAULT = AmountLayout(aligned = true)
    }
}

/** A saved starting point for a regular transaction, like "Coffee" or "Fuel". Its date is ignored. */
data class Template(
    val id: String,
    val name: String,
    val draft: TransactionDraft,
    /** When it recurs, for reminders (Settings › Reminders), or null. */
    val schedule: Schedule? = null,
)

data class DraftCheck(
    /** Problems with the transaction as a whole. */
    val errors: List<String>,
    /** Problems with one posting's account or amount, keyed by index into the draft's postings. */
    val accountErrors: Map<Int, String>,
    val amountErrors: Map<Int, String>,
    /** The posting whose amount was left blank, if any, and the amount it will get. */
    val inferredIndex: Int?,
    val inferred: MixedAmount,
    /** What the amounts add up to, when they don't balance. */
    val unbalancedBy: MixedAmount?,
) {
    val ok: Boolean get() = errors.isEmpty() && accountErrors.isEmpty() && amountErrors.isEmpty()
    val firstProblem: String?
        get() = errors.firstOrNull() ?: accountErrors.values.firstOrNull() ?: amountErrors.values.firstOrNull()
}

/** One line to write: a posting with its amount worked out. quantity is null only while the form can't be balanced. */
data class ResolvedPosting(
    val account: String,
    val commodity: String,
    val quantity: BigDecimal?,
    val comment: String,
    /** The amount was worked out, not typed. */
    val inferred: Boolean = false,
)

/**
 * Where a transaction is in the journal text: lines [firstLine, lastLine] (1-based, inclusive), and
 * what those lines said when the journal was read, joined with \n.
 */
data class TransactionLocation(val firstLine: Int, val lastLine: Int, val text: String)

class EntryException(message: String) : Exception(message)

/** Checking, writing, changing and deleting transactions entered in the app. */
object Entry {

    fun check(draft: TransactionDraft, styles: Styles): DraftCheck {
        val errors = ArrayList<String>()
        val accountErrors = HashMap<Int, String>()
        val amountErrors = HashMap<Int, String>()

        if (draft.payee.contains('|')) errors.add("The payee can't contain |. Put the rest in the note.")
        for ((label, s) in listOf("payee" to draft.payee, "note" to draft.note)) {
            if (s.contains(';')) errors.add("The $label can't contain ; because it starts a comment.")
            if (s.any { it == '\n' || it == '\r' }) errors.add("The $label must be one line.")
        }
        if (draft.description.firstOrNull()?.let { it in "*!(" } == true) {
            errors.add("The payee can't start with *, ! or (, which mean something else in a journal.")
        }
        if (draft.comment.any { it == '\n' || it == '\r' }) errors.add("The comment must be one line.")

        val used = draft.used
        val sums = LinkedHashMap<String, BigDecimal>()
        val blanks = ArrayList<Int>()
        for (i in used) {
            val p = draft.postings[i]
            accountProblem(p.account)?.let { accountErrors[i] = it }
            if (p.comment.any { it == '\n' || it == '\r' }) errors.add("Comments on accounts must be one line.")
            if (p.amount.isBlank()) {
                blanks.add(i)
                continue
            }
            val q = parseAmount(p.amount)
            if (q == null) {
                amountErrors[i] =
                    if (isExpression(p.amount)) "Not a sum this can work out, like 12.50+3.20" else "Not a number"
                continue
            }
            val c = p.commodity.trim()
            if (c.any { it == '"' || it == '\n' || it == '\r' }) {
                amountErrors[i] = "The commodity can't contain \""
                continue
            }
            sums.merge(c, q, BigDecimal::add)
        }
        if (used.size < 2) errors.add("A transaction needs at least two accounts.")
        if (blanks.size > 1) errors.add("Only one amount can be left empty.")

        val sum = MixedAmount.of(sums)
        var inferredIndex: Int? = null
        var inferred = MixedAmount.ZERO
        var unbalancedBy: MixedAmount? = null
        if (blanks.size == 1) {
            inferredIndex = blanks[0]
            inferred = -sum
        } else if (blanks.isEmpty() && amountErrors.isEmpty() && !sum.looksZero(styles)) {
            // Like the journal parser: two commodities with opposite signs is an implicit conversion.
            val conversion =
                sum.parts.size == 2 && sum.parts.values.let { v -> v.first().signum() != v.last().signum() }
            if (!conversion) {
                unbalancedBy = sum
                errors.add("The amounts don't balance: they add up to ${styles.format(sum)}.")
            }
        }
        return DraftCheck(errors, accountErrors, amountErrors, inferredIndex, inferred, unbalancedBy)
    }

    private fun accountProblem(account: String): String? {
        val a = account.trim()
        return when {
            a.isEmpty() -> "Enter an account"
            a.contains("  ") || a.contains('\t') -> "Account names can't contain two spaces in a row"
            a.contains(';') -> "Account names can't contain ;"
            a.first() == '(' || a.first() == '[' -> "Virtual postings aren't supported here"
            a.length > 1 && a[0] in "*!" && a[1] == ' ' -> "Account names can't start with * or !"
            a.startsWith(':') || a.endsWith(':') || a.contains("::") -> "Part of this account name is empty"
            else -> null
        }
    }

    /**
     * Read a typed amount: a number, or a simple sum like "12.50+3.20", "3*4.5", "(12+8)/2" or "-20+5".
     * Supports + - * / (× ÷ too) and brackets, with the usual precedence. Each number is read as by
     * [parseNumber]. Division is to 10 decimal places, then trailing zeros are dropped. Returns null if
     * the text isn't a number or a valid sum (or divides by zero).
     */
    fun parseAmount(text: String): BigDecimal? {
        val t = text.trim().replace('\u2212', '-').replace('×', '*').replace('÷', '/')
        // Spaces are allowed around operators and brackets, but not between two numbers ("2 3" isn't 23).
        if (Regex("[\\d.,]\\s+[\\d.,]").containsMatchIn(t)) return null
        val s = t.filterNot { it.isWhitespace() }
        if (s.isEmpty()) return null
        // A plain number is by far the most common: no expression machinery needed.
        if (s.none { it in "+*/()" } && s.lastIndexOf('-') <= 0) return parseNumber(s)
        return ExpressionParser(s).parse()?.stripTrailingZeros()?.let { if (it.scale() < 0) it.setScale(0) else it }
    }

    /** True if [text] is a sum rather than a single number, so the form can show its result. */
    fun isExpression(text: String): Boolean {
        val s = text.trim().replace('\u2212', '-')
        return s.any { it in "+*/()×÷" } || s.lastIndexOf('-') > 0
    }

    /** + - * / with brackets, by recursive descent: sum = term (± term)*, term = factor (×÷ factor)*. */
    private class ExpressionParser(private val s: String) {
        private var i = 0

        fun parse(): BigDecimal? = try {
            val v = sum()
            if (i != s.length) null else v
        } catch (e: ArithmeticException) {
            null
        }

        private fun sum(): BigDecimal? {
            var v = term() ?: return null
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term() ?: return null
                v = if (op == '+') v.add(r) else v.subtract(r)
            }
            return v
        }

        private fun term(): BigDecimal? {
            var v = factor() ?: return null
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                val r = factor() ?: return null
                v = if (op == '*') v.multiply(r)
                else v.divide(r, 10, java.math.RoundingMode.HALF_EVEN)
            }
            return v
        }

        private fun factor(): BigDecimal? {
            if (i >= s.length) return null
            return when (s[i]) {
                '-' -> {
                    i++; factor()?.negate()
                }

                '+' -> {
                    i++; factor()
                }

                '(' -> {
                    i++
                    val v = sum() ?: return null
                    if (i >= s.length || s[i] != ')') return null
                    i++
                    v
                }

                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) i++
                    if (i == start) null else parseNumber(s.substring(start, i))
                }
            }
        }
    }

    /**
     * Read a typed number: an optional sign, digits, and either decimal mark. As in journals, when both
     * marks appear the last one is the decimal mark, and a mark repeated alone is a digit group mark.
     * Returns null if the text isn't a number.
     */
    fun parseNumber(text: String): BigDecimal? {
        var s = text.filterNot { it.isWhitespace() }
        if (s.isEmpty()) return null
        var negative = false
        when (s[0]) {
            '-', '\u2212' -> {
                negative = true; s = s.substring(1)
            }

            '+' -> s = s.substring(1)
        }
        if (s.none { it.isDigit() } || s.any { !it.isDigit() && it != '.' && it != ',' }) return null
        val marks = s.filter { it == '.' || it == ',' }
        val decimalMark: Char? = when {
            marks.isEmpty() -> null
            marks.toSet().size == 2 -> marks.last()
            marks.length == 1 -> marks[0]
            else -> null
        }
        val k = if (decimalMark == null) s.length else s.lastIndexOf(decimalMark)
        val intPart = s.substring(0, k)
        val frac = if (decimalMark == null) "" else s.substring(k + 1)
        if (decimalMark != null && intPart.contains(decimalMark)) return null
        if (frac.any { !it.isDigit() }) return null
        // Digit groups must be groups of three, so a typo like 4.5.0 isn't read as 450.
        val groups = intPart.split('.', ',')
        if (groups.size > 1 && (groups[0].isEmpty() || groups.drop(1).any { it.length != 3 })) return null
        val digits = intPart.filter { it.isDigit() }.ifEmpty { "0" }
        val value = BigDecimal(if (frac.isEmpty()) digits else "$digits.$frac")
        return if (negative) value.negate() else value
    }

    /**
     * The postings to write, with the empty amount filled in with the calculated one. If the calculated
     * amount has several commodities, the account gets one posting per commodity, as hledger would infer.
     */
    fun resolve(draft: TransactionDraft, styles: Styles): List<ResolvedPosting> {
        val check = check(draft, styles)
        val out = ArrayList<ResolvedPosting>()
        for (i in draft.used) {
            val p = draft.postings[i]
            val account = p.account.trim()
            val comment = p.comment.trim()
            when {
                p.amount.isNotBlank() -> out.add(
                    ResolvedPosting(
                        account,
                        p.commodity.trim(),
                        parseAmount(p.amount),
                        comment
                    )
                )

                check.inferredIndex == i && check.inferred.isZero -> {
                    val commodity = p.commodity.trim().ifEmpty {
                        draft.postings.firstOrNull { it.amount.isNotBlank() && it.commodity.isNotBlank() }?.commodity?.trim()
                            .orEmpty()
                    }
                    out.add(ResolvedPosting(account, commodity, BigDecimal.ZERO, comment, inferred = true))
                }

                check.inferredIndex == i ->
                    check.inferred.parts.forEach { (c, q) -> out.add(ResolvedPosting(account, c, q, comment, inferred = true)) }

                else -> out.add(ResolvedPosting(account, p.commodity.trim(), null, comment))
            }
        }
        return out
    }

    /**
     * The transaction as journal text, without a final newline. Every amount is written, including
     * the calculated one, by default with four spaces after the account name ([layout] changes this):
     *
     *     2026-10-27 * Corner Cafe | flat white  ; comment
     *         expenses:food:dining    $4.50
     *         assets:cash    $-4.50
     *
     * Amounts use the journal's style for their commodity, at full precision and without digit
     * group marks (which could be read as a decimal mark).
     */
    fun render(
        draft: TransactionDraft,
        styles: Styles,
        newline: String = "\n",
        layout: AmountLayout = AmountLayout.DEFAULT,
    ): String {
        val header = buildString {
            append(draft.date)
            when (draft.status) {
                Status.CLEARED -> append(" *")
                Status.PENDING -> append(" !")
                Status.UNMARKED -> Unit
            }
            if (draft.description.isNotEmpty()) append(' ').append(draft.description)
            if (draft.comment.isNotBlank()) append("  ; ").append(draft.comment.trim())
        }
        var rows = resolve(draft, styles)
        if (!layout.padDecimals) {
            // A worked-out amount gets as many decimals as the typed ones in its commodity: -4.50, not -4.5.
            val scales = rows.filter { !it.inferred }.mapNotNull { p -> p.quantity?.let { p.commodity to it.scale() } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, s) -> s.max() }
            rows = rows.map { p ->
                val q = p.quantity
                val s = scales[p.commodity]
                if (!p.inferred || q == null || s == null || q.scale() >= s) p else p.copy(quantity = q.setScale(s))
            }
        }
        val amounts = rows.map { p -> p.quantity?.let { amountText(p.commodity, it, styles, layout.padDecimals) } }
        // Aligned: the column where every decimal mark goes, moved right if an account is too long for it.
        // Lines start with a 4-space indent, and at least 2 spaces separate the account from the amount.
        val decimalColumn = if (!layout.aligned) 0 else {
            val withAmounts = rows.indices.filter { amounts[it] != null }
            val maxAccount = withAmounts.maxOfOrNull { rows[it].account.length } ?: 0
            val maxLeft = withAmounts.maxOfOrNull { amounts[it]!!.second } ?: 0
            maxOf(layout.column.coerceIn(AmountLayout.MIN_COLUMN, AmountLayout.MAX_COLUMN), maxAccount + 7 + maxLeft)
        }
        val lines = rows.mapIndexed { i, p ->
            buildString {
                append(INDENT).append(p.account)
                val amount = amounts[i]
                if (amount != null) {
                    val (text, left) = amount
                    val gap = if (layout.aligned) {
                        // The amount starts left of the decimal mark by its integer part's width.
                        (decimalColumn - 1 - left) - (INDENT.length + p.account.length)
                    } else layout.gap
                    append(" ".repeat(gap.coerceAtLeast(AmountLayout.MIN_SPACES))).append(text)
                }
                if (p.comment.isNotEmpty()) append("  ; ").append(p.comment)
            }
        }
        return (listOf(header) + lines).joinToString(newline)
    }

    private const val INDENT = "    "

    /**
     * An amount as written, and how many characters come before its decimal mark (or, without one, before
     * the end of the number): the symbol on the left, the sign and the integer digits, like hledger's
     * amountIntegerWidth. Used to line up decimal marks.
     */
    internal fun amountText(commodity: String, quantity: BigDecimal, styles: Styles, pad: Boolean = true): Pair<String, Int> {
        val text = formatExact(commodity, quantity, styles, pad)
        val st = styles.styleFor(commodity)
        val number = formatExact("", quantity, Styles(mapOf("" to st)), pad)
        val prefix = if (commodity.isNotEmpty() && st.side == Side.LEFT) text.length - number.length else 0
        val mark = number.indexOf(st.decimalMark)
        return text to prefix + (if (mark >= 0) mark else number.length)
    }

    internal fun formatExact(commodity: String, quantity: BigDecimal, styles: Styles, pad: Boolean = true): String {
        val st = styles.styleFor(commodity)
        val scale = quantity.stripTrailingZeros().scale().coerceAtLeast(0)
        // Without padding: the decimals the amount has (4.50 stays 4.50, 4 stays 4); a long worked-out one
        // is cut back to the digits that matter.
        val precision = if (pad) maxOf(st.precision, scale)
        else if (quantity.scale() in 0..maxOf(st.precision, scale)) quantity.scale() else scale
        val exact = st.copy(precision = precision, groupMark = null, groupSizes = emptyList())
        return Styles(mapOf(commodity to exact)).format(commodity, quantity)
    }

    // ------------------------------------------------------------------ changing the journal text

    /**
     * The journal text with the transaction added at the end, after a blank line. The result is parsed
     * again and must contain the transaction exactly as entered: this catches a journal that ends inside
     * a comment block or an apply account / alias that would rename the accounts, an amount the journal
     * would read differently, and errors in the rest of the journal.
     */
    fun append(
        existing: String,
        draft: TransactionDraft,
        styles: Styles,
        today: LocalDate = LocalDate.now(),
        layout: AmountLayout = AmountLayout.DEFAULT,
    ): String = existing + addition(existing, draft, styles, today, layout)

    /**
     * The text to add to the end of [existing] for [draft]: a newline if the file doesn't end with one,
     * a blank line, and the transaction. Checked like [append]; only this is written to the file.
     */
    fun addition(
        existing: String,
        draft: TransactionDraft,
        styles: Styles,
        today: LocalDate = LocalDate.now(),
        layout: AmountLayout = AmountLayout.DEFAULT,
    ): String {
        check(draft, styles).firstProblem?.let { throw EntryException(it) }
        val nl = newlineOf(existing)
        val sb = StringBuilder()
        if (existing.isNotEmpty()) {
            if (!existing.endsWith("\n")) sb.append(nl)
            // The last line, ignoring a final newline: if it has text, add a blank line after it.
            val end = existing.length - (if (existing.endsWith("\n")) 1 else 0)
            val lastLine = existing.substring(existing.lastIndexOf('\n', end - 1) + 1, end)
            if (lastLine.isNotBlank()) sb.append(nl)
        }
        val startLine = 1 + countNewlines(existing) + countNewlines(sb.toString())
        sb.append(render(draft, styles, nl, layout)).append(nl)
        val added = sb.toString()
        verify(existing + added, startLine, draft, styles, today, NOTHING_ADDED)
        return added
    }

    /** Where [t] is in [text], the journal text it was read from. */
    fun locate(text: String, t: Transaction): TransactionLocation? {
        val lines = Lines(text)
        if (t.line < 1 || t.lastLine < t.line || t.lastLine > lines.count) return null
        return TransactionLocation(t.line, t.lastLine, (t.line..t.lastLine).joinToString("\n") { lines.content(it) })
    }

    /**
     * The journal text with the transaction at [at] replaced by [draft]. Refuses if those lines have
     * changed since the journal was read (eg it was edited elsewhere and synced), and checks the new
     * transaction reads back as entered.
     */
    fun replace(
        existing: String,
        at: TransactionLocation,
        draft: TransactionDraft,
        styles: Styles,
        today: LocalDate = LocalDate.now(),
        layout: AmountLayout = AmountLayout.DEFAULT,
    ): String {
        check(draft, styles).firstProblem?.let { throw EntryException(it) }
        val lines = checkUnchanged(existing, at)
        val nl = newlineOf(existing)
        val text = existing.substring(0, lines.start(at.firstLine)) +
                render(draft, styles, nl, layout) + nl +
                existing.substring(lines.endWithNewline(at.lastLine))
        verify(text, at.firstLine, draft, styles, today, NOTHING_CHANGED)
        return text
    }

    /** The journal text without the transaction at [at], and without a blank line it leaves doubled. */
    fun delete(existing: String, at: TransactionLocation, today: LocalDate = LocalDate.now()): String {
        val lines = checkUnchanged(existing, at)
        val prevBlank = at.firstLine > 1 && lines.content(at.firstLine - 1).isBlank()
        val next = at.lastLine + 1
        val nextExists = next <= lines.count && lines.start(next) < existing.length
        val nextBlank = nextExists && lines.content(next).isBlank()
        var from = lines.start(at.firstLine)
        var to = lines.endWithNewline(at.lastLine)
        if (nextBlank && (prevBlank || at.firstLine == 1)) to = lines.endWithNewline(next)
        else if (!nextExists && prevBlank) from = lines.start(at.firstLine - 1)
        val text = existing.substring(0, from) + existing.substring(to)
        try {
            // Removing whole transaction lines can't change how other transactions read, only directives
            // could be affected, so only they are checked.
            JournalParser(today, keepTransaction = { false }).parse(text)
        } catch (e: JournalException) {
            throw EntryException("The journal wouldn't read correctly without this transaction, so it wasn't deleted: ${e.message}")
        }
        return text
    }

    /**
     * The journal text with the transaction at [at] marked [status] (cleared *, pending ! or unmarked).
     * Only the status mark on its first line changes; everything else, including how it's written, stays
     * as it was, so this works for any transaction, even ones the form can't edit.
     */
    fun setStatus(
        existing: String,
        at: TransactionLocation,
        status: Status,
        today: LocalDate = LocalDate.now()
    ): String {
        val lines = checkUnchanged(existing, at)
        val header = lines.content(at.firstLine)
        val newHeader = headerWithStatus(header, status)
        if (newHeader == header) return existing
        val start = lines.start(at.firstLine)
        val text = existing.substring(0, start) + newHeader + existing.substring(start + header.length)
        val t = try {
            JournalParser(
                today,
                keepTransaction = { it == at.firstLine }).parse(text).transactions.firstOrNull { it.line == at.firstLine }
        } catch (e: JournalException) {
            null
        }
        if (t == null || t.status != status) throw EntryException("The transaction's status couldn't be changed. Nothing was changed.")
        return text
    }

    /** A transaction's first line with its status mark replaced: "2026-10-27 * Cafe" etc. */
    internal fun headerWithStatus(header: String, status: Status): String {
        // The date (and =date2) runs to the first space or ;. Then an optional * or !, then spaces.
        val dateEnd = header.indexOfFirst { it.isWhitespace() || it == ';' }.let { if (it < 0) header.length else it }
        var i = dateEnd
        while (i < header.length && (header[i] == ' ' || header[i] == '\t')) i++
        val hasMark = i < header.length && (header[i] == '*' || header[i] == '!')
        var restStart = if (hasMark) i + 1 else i
        if (hasMark) while (restStart < header.length && (header[restStart] == ' ' || header[restStart] == '\t')) restStart++
        val rest = header.substring(restStart)
        val mark = when (status) {
            Status.CLEARED -> "*"
            Status.PENDING -> "!"
            Status.UNMARKED -> ""
        }
        val date = header.substring(0, dateEnd)
        return buildString {
            append(date)
            if (mark.isNotEmpty()) append(' ').append(mark)
            if (rest.isNotEmpty()) append(' ').append(rest)
        }
    }

    private fun checkUnchanged(existing: String, at: TransactionLocation): Lines {
        val lines = Lines(existing)
        val now =
            if (at.lastLine <= lines.count) (at.firstLine..at.lastLine).joinToString("\n") { lines.content(it) } else null
        if (now != at.text) {
            throw EntryException("This transaction has changed in the file since it was opened, maybe by a sync. Reload and try again. Nothing was changed.")
        }
        return lines
    }

    private fun newlineOf(text: String) = if (text.contains("\r\n")) "\r\n" else "\n"

    private const val NOTHING_ADDED = "Nothing was added."
    private const val NOTHING_CHANGED = "Nothing was changed."

    private fun verify(
        text: String,
        startLine: Int,
        draft: TransactionDraft,
        styles: Styles,
        today: LocalDate,
        nothing: String
    ) {
        // Only the new transaction is read in full, with every directive (aliases, apply account, decimal
        // marks, comment blocks, default commodity, year) that affects it. The rest of the journal was
        // read when it was opened, so reading every transaction again would only make saving slow.
        val journal = try {
            JournalParser(today, keepTransaction = { it == startLine }).parse(text)
        } catch (e: JournalException) {
            if (e.line in 1 until startLine) {
                throw EntryException("The journal has a problem at line ${e.line}: ${e.message} $nothing")
            }
            throw EntryException("The transaction wouldn't read back correctly: ${e.message} $nothing")
        }
        val t = journal.transactions.firstOrNull { it.line == startLine }
            ?: throw EntryException("The transaction would be inside a comment block, so it wouldn't be read. $nothing")
        if (t.date != draft.date || t.status != draft.status || t.description != draft.description || t.comment != draft.comment.trim()) {
            throw EntryException("The transaction's description wouldn't read back as entered. $nothing")
        }
        val rows = resolve(draft, styles)
        if (t.postings.size != rows.size) throw EntryException("The transaction wouldn't read back as entered. $nothing")
        for ((k, row) in rows.withIndex()) {
            val parsed = t.postings.filter { it.line == startLine + 1 + k }
            if (parsed.size != 1 || parsed[0].account != row.account) {
                throw EntryException("This journal's alias or apply account directives would change the account ${row.account}. $nothing")
            }
            val a = parsed[0].amount
            // With no commodity written, the journal's default commodity (D directive) may apply.
            if (row.quantity == null || a.quantity.compareTo(row.quantity) != 0 || (row.commodity.isNotEmpty() && a.commodity != row.commodity)) {
                throw EntryException(
                    "The amount for ${row.account} wouldn't read back as entered, maybe because of this journal's decimal mark. $nothing"
                )
            }
            if (parsed[0].comment != row.comment) throw EntryException("The comment on ${row.account} wouldn't read back as entered. $nothing")
        }
    }

    /** The journal text split into lines the way the parser counts them, keeping every character. */
    private class Lines(private val text: String) {
        private val starts: IntArray = run {
            val l = ArrayList<Int>(text.length / 40 + 16)
            l.add(if (text.startsWith("\uFEFF")) 1 else 0)
            var nl = text.indexOf('\n')
            while (nl >= 0) {
                l.add(nl + 1)
                nl = text.indexOf('\n', nl + 1)
            }
            l.toIntArray()
        }

        val count: Int get() = starts.size
        fun start(line: Int): Int = starts[line - 1]

        /** Where the next line starts, or the end of the text. */
        fun endWithNewline(line: Int): Int = if (line < starts.size) starts[line] else text.length
        fun content(line: Int): String =
            text.substring(start(line), endWithNewline(line)).removeSuffix("\n").removeSuffix("\r")
    }

    // ------------------------------------------------------------------ from existing transactions

    /**
     * Why the form can't edit [t] without losing something, as phrases like "balance assertions". Empty
     * if it can. [source] is the transaction's text in the journal.
     */
    fun editProblems(t: Transaction, source: String?): List<String> {
        val out = LinkedHashSet<String>()
        if (t.file != null) out.add("it's in ${t.file}, an included file, which is only read here (change it there)")
        else if (t.line <= 0 || source == null) out.add("its place in the file isn't known")
        if (t.code.isNotEmpty()) out.add("a code (${t.code})")
        if (t.date2 != null) out.add("a secondary date")
        if (t.comment.contains('\n')) out.add("a comment on several lines")
        for (p in t.postings) {
            if (p.generated) continue
            if (p.kind != PostingKind.REAL) out.add("virtual postings")
            if (p.amount.cost != null) out.add("costs (@ or @@)")
            if (p.assertion != null) out.add("balance assertions")
            if (p.status != Status.UNMARKED) out.add("postings marked cleared or pending")
            if (p.comment.contains('\n')) out.add("an account comment on several lines")
        }
        if (source != null && source.lines().drop(1)
                .any { splitComment(it).first.contains('{') }
        ) out.add("lot prices ({ })")
        return out.toList()
    }

    /**
     * A draft from [t] on [date]: its real postings, with the calculated amount left blank so it is
     * calculated again. With [keepComments], also its comments (for editing it).
     */
    fun fromTransaction(
        t: Transaction,
        styles: Styles,
        date: LocalDate,
        keepComments: Boolean = false
    ): TransactionDraft {
        // An inferred amount in several commodities becomes one posting per commodity; keep the first.
        val seenLines = HashSet<Int>()
        val real =
            t.postings.filter { it.kind == PostingKind.REAL && !it.generated && (it.line == 0 || seenLines.add(it.line)) }
        // Leave blank the amount hledger calculated (not a balance assignment's, which needs its assertion).
        val blank = real.indexOfFirst { it.inferred && it.assertion == null }
        val postings = real.mapIndexed { i, p ->
            val comment = if (keepComments) p.comment else ""
            if (i == blank) DraftPosting(p.account, comment = comment)
            else DraftPosting(
                p.account,
                amountInput(p.amount.quantity, styles.styleFor(p.amount.commodity)),
                p.amount.commodity,
                comment,
            )
        }
        return TransactionDraft(
            date = date,
            status = t.status,
            payee = t.description.substringBefore('|').trim(),
            note = if (t.description.contains('|')) t.description.substringAfter('|').trim() else "",
            comment = if (keepComments) t.comment else "",
            postings = postings + List((2 - postings.size).coerceAtLeast(0)) { DraftPosting() },
        )
    }

    /** A quantity as it would be typed in the form, eg "4.50" or "4,50". */
    fun amountInput(quantity: BigDecimal, style: AmountStyle): String {
        val scale = maxOf(style.precision, quantity.stripTrailingZeros().scale().coerceAtLeast(0))
        val plain = quantity.setScale(scale, java.math.RoundingMode.HALF_EVEN).toPlainString()
        return if (style.decimalMark == ',') plain.replace('.', ',') else plain
    }
}

/**
 * Templates for the "Template" box in the add form, best first: names starting with [text], then names
 * with a word starting with it, then names containing it, then templates whose payee contains it.
 * Case is ignored. Empty [text] matches nothing, so a long list of templates never fills the screen.
 */
fun templatesMatching(templates: List<Template>, text: String, limit: Int = 6): List<Template> {
    val q = text.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    fun rank(t: Template): Int? {
        val name = t.name.lowercase()
        return when {
            name.startsWith(q) -> 0
            name.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(q) } -> 1
            name.contains(q) -> 2
            t.draft.payee.lowercase().contains(q) -> 3
            else -> null
        }
    }
    return templates.mapNotNull { t -> rank(t)?.let { it to t } }
        .sortedWith(compareBy<Pair<Int, Template>> { it.first }.thenBy { it.second.name.lowercase() })
        .take(limit).map { it.second }
}
