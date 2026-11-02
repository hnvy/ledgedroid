package app.ledgedroid.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A subset of hledger's query language, for the search box:
 *
 *   food            account name contains "food" (regex, case-insensitive)
 *   acct:REGEX      same, explicitly
 *   desc:REGEX      transaction description matches
 *   payee:REGEX     the part of the description before |
 *   cur:REGEX       commodity symbol matches exactly (eg cur:\$ or cur:EUR)
 *   status:* / status:! / status:   cleared / pending / unmarked
 *   date:PERIOD     transaction date in a period: date:2026, date:2026-10, date:2026-10-27,
 *                   date:2026q3, date:2026-01..2026-04, date:2026-09.. or date:..2026-01 (end exclusive)
 *   amt:N           posting amount: amt:<10 amt:>=100 amt:=4.5; amt:4.5 compares the absolute
 *                   value, amt:-4.5 or amt:+4.5 the signed one, and so does an unsigned zero
 *                   (amt:<0 finds negative amounts), as in hledger
 *   tag:NAME[=VAL]  transaction or posting has a tag whose name matches NAME (and value matches VAL),
 *                   eg tag:trip or tag:trip=lisbon; a posting has its transaction's tags too
 *   depth:N         show at most N account levels
 *   not:TERM        negate any of the above
 *   "two words"     quote terms containing spaces
 *
 * Like hledger, terms of the same kind are ORed and different kinds are ANDed.
 */
class Query private constructor(private val terms: List<Term>, val depth: Int?) {

    enum class Field { ACCT, DESC, PAYEE, CUR, STATUS, DATE, AMT, TAG }

    enum class Comparison { LT, LE, GT, GE, EQ }

    class Term(
        val field: Field,
        val negated: Boolean,
        val regex: Regex? = null,
        val status: Status? = null,
        val span: DateSpan? = null,
        val comparison: Comparison? = null,
        val amount: BigDecimal? = null,
        /** amt: with an unsigned non-zero number compares the absolute value. */
        val absolute: Boolean = false,
        val valueRegex: Regex? = null,
    )

    val isEmpty: Boolean get() = terms.isEmpty()


    /**
     * Does this posting match? [originalAccount] is the account a posting had before a report renamed it
     * (eg the budget report's <unbudgeted>); like hledger, account terms match either name.
     */
    fun matches(t: Transaction, p: Posting, originalAccount: String? = null): Boolean {
        if (terms.isEmpty()) return true
        val positive = terms.filter { !it.negated }.groupBy { it.field }
        for ((_, group) in positive) if (group.none { test(it, t, p, originalAccount) }) return false
        for (term in terms) if (term.negated && test(term, t, p, originalAccount)) return false
        return true
    }

    /** Does any posting of [t] match, like hledger's register/print queries? */
    fun matchesTransaction(t: Transaction): Boolean = terms.isEmpty() || t.postings.any { matches(t, it) }

    /**
     * Does a commodity pass this query's cur: terms? Auto posting multipliers only multiply the
     * matched amounts in commodities the rule's query selects.
     */
    fun matchesCommodity(commodity: String): Boolean {
        val cur = terms.filter { it.field == Field.CUR }
        if (cur.isEmpty()) return true
        val pos = cur.filter { !it.negated }
        if (pos.isNotEmpty() && pos.none { it.regex!!.matches(commodity) }) return false
        return cur.none { it.negated && it.regex!!.matches(commodity) }
    }

    /**
     * This query, also leaving out [accounts] and their subaccounts (the accounts hidden in the app). Like
     * adding not:acct:^NAME(:|$) for each.
     */
    fun excludingAccounts(accounts: Collection<String>): Query {
        if (accounts.isEmpty()) return this
        val extra = accounts.map { a -> Term(Field.ACCT, negated = true, regex = Regex("^" + Regex.escape(a) + "(:|$)")) }
        return Query(terms + extra, depth)
    }

    /** The span of this query's date: term, if it has exactly one (not negated); used for forecasting. */
    val dateSpan: DateSpan?
        get() = terms.filter { it.field == Field.DATE && !it.negated }.singleOrNull()?.span

    /** The parts of this query that apply to budget goals: account, commodity and depth terms (hledger's budgetGoalQuery). */
    fun goalQuery(): Query = Query(terms.filter { it.field == Field.ACCT || it.field == Field.CUR }, depth)

    private fun test(term: Term, t: Transaction, p: Posting, originalAccount: String?): Boolean = when (term.field) {
        Field.ACCT -> term.regex!!.containsMatchIn(p.account) ||
                (originalAccount != null && term.regex.containsMatchIn(originalAccount))

        Field.DESC -> term.regex!!.containsMatchIn(t.description)
        Field.PAYEE -> term.regex!!.containsMatchIn(t.description.substringBefore('|').trim())
        Field.CUR -> term.regex!!.matches(p.amount.commodity)
        Field.STATUS -> (if (p.status != Status.UNMARKED) p.status else t.status) == term.status
        Field.DATE -> term.span!!.contains(t.date)
        Field.AMT -> compare(term, p.amount.quantity)
        Field.TAG -> (tagsOf(t) + tagsIn(p.comment)).any { (name, value) ->
            term.regex!!.containsMatchIn(name) && (term.valueRegex == null || term.valueRegex.containsMatchIn(value))
        }
    }

    private fun compare(term: Term, quantity: BigDecimal): Boolean {
        val q = if (term.absolute) quantity.abs() else quantity
        val c = q.compareTo(term.amount)
        return when (term.comparison!!) {
            Comparison.LT -> c < 0
            Comparison.LE -> c <= 0
            Comparison.GT -> c > 0
            Comparison.GE -> c >= 0
            Comparison.EQ -> c == 0
        }
    }

    /**
     * A transaction's own tags, cached because every posting of it is tested. Keyed by identity: hashing
     * a Transaction by value would hash all its postings each time.
     */
    private val tagCache = java.util.IdentityHashMap<Transaction, List<Pair<String, String>>>()
    private fun tagsOf(t: Transaction): List<Pair<String, String>> {
        if (t.comment.isEmpty()) return emptyList()
        return synchronized(tagCache) { tagCache.getOrPut(t) { tagsIn(t.comment) } }
    }

    companion object {
        val ALL = Query(emptyList(), null)

        /**
         * Parse a query string. Throws IllegalArgumentException with a user-facing message on bad input.
         * [today] is only used for dates without a year, like hledger.
         */
        fun parse(input: String, today: LocalDate = LocalDate.now()): Query = parseTerms(tokenize(input), today)

        /** Parse query terms already split into words (quotes removed), eg by [ReportCommand]. */
        fun parseTerms(words: List<String>, today: LocalDate = LocalDate.now()): Query {
            val terms = ArrayList<Term>()
            var depth: Int? = null
            for (word in words) {
                var w = word
                var negated = false
                while (w.startsWith("not:")) {
                    negated = !negated; w = w.removePrefix("not:")
                }
                // Only hledger's query prefixes count; "income:wage" is just an account pattern.
                val prefix = Regex("^([a-z0-9]+):").find(w)?.groupValues?.get(1)?.takeIf { it in PREFIXES }
                val arg = if (prefix != null) w.substring(prefix.length + 1) else w
                if (arg.isEmpty() && prefix != "status") continue // an unfinished term, eg while typing
                when (prefix) {
                    null, "acct" -> terms.add(Term(Field.ACCT, negated, regex(arg)))
                    "desc" -> terms.add(Term(Field.DESC, negated, regex(arg)))
                    "payee" -> terms.add(Term(Field.PAYEE, negated, regex(arg)))
                    "cur" -> terms.add(Term(Field.CUR, negated, regex(arg)))
                    "status" -> {
                        val st = when (arg) {
                            "*" -> Status.CLEARED
                            "!" -> Status.PENDING
                            "" -> Status.UNMARKED
                            else -> throw IllegalArgumentException("status: takes *, ! or nothing")
                        }
                        terms.add(Term(Field.STATUS, negated, status = st))
                    }

                    "date" -> terms.add(Term(Field.DATE, negated, span = dateSpan(arg, today)))
                    "amt" -> terms.add(amountTerm(arg, negated))
                    "tag" -> {
                        val eq = arg.indexOf('=')
                        val name = if (eq < 0) arg else arg.substring(0, eq)
                        val value = if (eq < 0) null else arg.substring(eq + 1)
                        if (name.isEmpty()) throw IllegalArgumentException("tag: needs a tag name, like tag:trip")
                        terms.add(Term(Field.TAG, negated, regex(name), valueRegex = value?.let { regex(it) }))
                    }

                    "depth" -> depth = arg.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("depth: needs a number of 1 or more")

                    "date2" -> throw IllegalArgumentException("date2: isn't supported; use date:")
                    else -> throw IllegalArgumentException("$prefix: isn't supported here")
                }
            }
            return Query(terms, depth)
        }

        /** date:2026, date:2026-10, date:2026q3, date:2026-01..2026-04, date:2026-09.., date:..2026-01 */
        private fun dateSpan(arg: String, today: LocalDate): DateSpan {
            fun fail(): Nothing = throw IllegalArgumentException(
                "date: takes a year, month or day, or a range: date:2026, date:2026-10, date:2026-01..2026-04"
            )

            fun one(s: String): DateSpan {
                // A month and day without a year, eg 10-27, uses the current year.
                val parts = s.split('-', '/', '.')
                if (parts.size == 2 && parts[0].length <= 2) return periodDate("${today.year}-$s") ?: fail()
                return periodDate(s.lowercase()) ?: fail()
            }

            val range = arg.indexOf("..")
            if (range < 0) return one(arg)
            val from = arg.substring(0, range)
            val to = arg.substring(range + 2)
            if (from.isEmpty() && to.isEmpty()) fail()
            val start = if (from.isEmpty()) null else one(from).start
            val end = if (to.isEmpty()) null else one(to).start
            if (start != null && end != null && !start.isBefore(end)) {
                throw IllegalArgumentException("date: the range's end must be after its start (the end isn't included)")
            }
            return DateSpan(start, end)
        }

        /**
         * amt:<10, amt:>=-5, amt:=4.5, amt:4.5 (absolute value), amt:-4.5 (signed). Like hledger's
         * parseAmountQueryTerm, an unsigned zero is compared signed, which is the useful reading:
         * amt:<0 finds negative amounts and amt:>0 positive ones (compared as absolute values, nothing
         * would be below zero and everything but zero above it).
         */
        private fun amountTerm(arg: String, negated: Boolean): Term {
            val m = Regex("^(<=|>=|<|>|=)?\\s*([+-])?\\s*(\\d+(?:[.,]\\d+)?)$").find(arg)
                ?: throw IllegalArgumentException("amt: takes a number with an optional <, <=, >, >= or =, like amt:>100")
            val (op, sign, number) = m.destructured
            val comparison = when (op) {
                "<" -> Comparison.LT
                "<=" -> Comparison.LE
                ">" -> Comparison.GT
                ">=" -> Comparison.GE
                else -> Comparison.EQ
            }
            val n = BigDecimal(number.replace(',', '.'))
            return Term(
                Field.AMT, negated,
                comparison = comparison,
                amount = if (sign == "-") n.negate() else n,
                absolute = sign.isEmpty() && n.signum() != 0,
            )
        }

        private val PREFIXES = setOf(
            "acct", "desc", "payee", "cur", "status", "depth", "date", "amt", "tag",
            // recognised so they give a clear message rather than matching account names:
            "date2", "code", "note", "real", "type", "expr", "any", "all",
        )

        private fun regex(s: String): Regex = try {
            Regex(s, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            throw IllegalArgumentException("\"$s\" isn't a valid regular expression")
        }

        internal fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            for (c in s) {
                when {
                    quote != null && c == quote -> quote = null
                    quote != null -> cur.append(c)
                    c == '"' || c == '\'' -> quote = c
                    c.isWhitespace() -> if (cur.isNotEmpty()) {
                        out.add(cur.toString()); cur.clear()
                    }

                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}
