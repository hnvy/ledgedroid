package app.ledgedroid.core

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Reads hledger journal text. Supports the parts of the format that matter for balance reports:
 *
 *  - transactions: date[=date2] [*|!] [(code)] description [; comment]
 *  - postings: account, amount, @/@@ costs, = == =* ==* balance assertions/assignments,
 *    (virtual) and [balanced virtual] accounts, at most one missing amount per group
 *  - amounts: left/right commodity symbols, "quoted symbols", either decimal mark, digit groups
 *  - directives: account (with type: tag), commodity (incl. format subdirective), decimal-mark,
 *    payee, D, Y/year, alias (plain and /regex/), end aliases, apply account / end apply account,
 *    comment blocks
 *
 * Periodic transaction rules (~) are kept for budget reports. Auto posting rules (=) are applied
 * when [auto] is true, like hledger --auto.
 *
 * Market prices (P) are read for valuation (-V, -X). Included files are read when [JournalParser.parse] is
 * given a way to find them (see [Includes]). Timeclock/timedot and lot tracking are skipped. Anything
 * skipped that could change balances is reported as a warning.
 */
class JournalParser(
    private val today: LocalDate = LocalDate.now(),
    private val auto: Boolean = false,
    /**
     * If set, only transactions whose first line passes this are read; the others are skipped without
     * looking inside them, exactly as the parser skips their lines anyway. Directives are all read.
     * Used to check a transaction in the context of the journal quickly.
     */
    private val keepTransaction: ((Int) -> Boolean)? = null,
) {
    fun parse(text: String): Journal = ParseRun(text, today, auto, keepTransaction).run()

    /**
     * Parse [text] with its include directives read through [includes] (see [Includes]). Line numbers stay
     * those of each file; what comes from an included file is marked with its name. Errors in an included
     * file say which file they're in.
     */
    fun parse(text: String, includes: IncludeResolver?): Journal {
        if (includes == null || !Includes.mentioned(text)) return parse(text)
        val x = Includes.expand(text, includes)
        val journal = try {
            ParseRun(x.text, today, auto, keepTransaction).run()
        } catch (e: JournalException) {
            val (file, line) = x.origin(e.line)
            if (file == null) throw JournalException(line, e.message.orEmpty(), e.excerpt)
            throw JournalException(0, "In $file, line $line: ${e.message}", e.excerpt)
        }
        return Includes.remap(journal, x)
    }
}

internal class RawPosting(
    val account: String,
    val kind: PostingKind,
    val status: Status,
    val amount: Amount?,
    val assertion: BalanceAssertion?,
    var comment: String,
    val line: Int,
    val isMultiplier: Boolean = false,
    val inferred: Boolean = false,
    val generated: Boolean = false,
)

internal class RawTransaction(
    val index: Int,
    val date: LocalDate,
    val date2: LocalDate?,
    val status: Status,
    val code: String,
    val description: String,
    var comment: String,
    val line: Int,
) {
    val postings = ArrayList<RawPosting>()
    var lastLine = line
}

private class LexedAmount(
    val commodity: String?,
    val side: Side?,
    val spaced: Boolean,
    val negative: Boolean,
    val number: String,
)

private class ParsedNumber(
    val value: BigDecimal,
    val decimalMark: Char?,
    val groupMark: Char?,
    val groupSizes: List<Int>,
    val precision: Int,
)

/** What we've seen of one commodity's written style (first-seen style, maximum precision). */
private class ObservedStyle(
    val side: Side,
    val spaced: Boolean,
    var decimalMark: Char?,
    var groupMark: Char?,
    var groupSizes: List<Int>,
    var precision: Int,
) {
    fun see(n: ParsedNumber) {
        if (decimalMark == null) decimalMark = n.decimalMark
        if (groupMark == null && n.groupMark != null) {
            groupMark = n.groupMark; groupSizes = n.groupSizes
        }
        precision = maxOf(precision, n.precision)
    }
}

private class ParseRun(
    text: String,
    private val today: LocalDate,
    private val auto: Boolean,
    private val keepTransaction: ((Int) -> Boolean)?,
) {
    private val lines = LineIndex(text.removePrefix("\uFEFF"))

    private var year: Int? = null
    private var fileDecimalMark: Char? = null
    private var defaultCommodity: String? = null
    private val declaredStyles = HashMap<String, AmountStyle>()
    private val declaredMarks = HashMap<String, Char>()
    private val primaryObs = LinkedHashMap<String, ObservedStyle>()
    private val secondaryObs = LinkedHashMap<String, ObservedStyle>()

    private val aliases = ArrayList<(String) -> String>()
    private val applyAccounts = ArrayList<String>()
    private val accountDecls = LinkedHashMap<String, AccountType?>()
    private val payeeDecls = LinkedHashSet<String>()
    private val prices = ArrayList<MarketPrice>()

    private val warnings = ArrayList<JournalIssue>()
    private val txns = ArrayList<RawTransaction>()

    private var current: RawTransaction? = null
    private var block = Block.NONE
    private var blockSubject = ""
    private var skippedIncludes = 0

    private val periodicRules = ArrayList<PeriodicRule>()
    private val autoRules = ArrayList<AutoRule>()

    /** The rule being read: its header, and its postings so far. */
    private var ruleHeader: Any? = null
    private val rulePostings = ArrayList<RawPosting>()
    private var ruleLastLine = 0

    private enum class Block { NONE, SKIP, ACCOUNT, COMMODITY, COMMENT, PERIODIC, AUTO }

    private class PeriodicHeader(
        val text: String,
        val expr: PeriodExpr,
        val status: Status,
        val description: String,
        val line: Int
    )

    private class AutoHeader(val text: String, val query: Query, val line: Int)

    fun run(): Journal {
        for (i in 0 until lines.size) {
            // Lines inside a skipped transaction are passed over without being copied out of the text.
            if (block == Block.SKIP && lines.isIndentedText(i)) continue
            val line = lines[i]
            val lineNo = i + 1
            try {
                handleLine(line, lineNo)
            } catch (e: JournalException) {
                throw e
            } catch (e: Exception) {
                throw JournalException(lineNo, e.message ?: e.toString(), line)
            }
        }
        finishTransaction()
        finishRule()
        summarizeSkipped()

        val styles = buildStyles()
        // Check periodic rules balance now, so a mistake is reported with its line number.
        for (r in periodicRules) {
            r.dates(DateSpan(r.span.start ?: today, (r.span.start ?: today).plusYears(1))).firstOrNull()?.let { d ->
                balanceTransactions(listOf(ruleRawTransaction(r, d, 0)), styles, lines, checkAssertions = false)
            }
        }

        val applyAuto = auto && autoRules.isNotEmpty()
        val toBalance = if (!applyAuto) txns else {
            // Like hledger: balance once to infer missing amounts, add auto postings, then balance again.
            val (prelim, _) = balanceTransactions(txns, styles, lines, checkAssertions = false)
            prelim.map { applyAutoRules(it, autoRules).toRaw() }
        }
        val (transactions, assertionIssues) = balanceTransactions(toBalance, styles, lines)
        return Journal(
            transactions = transactions,
            styles = styles,
            declaredAccounts = accountDecls.keys.toList(),
            declaredTypes = accountDecls.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
            declaredPayees = payeeDecls.toList(),
            prices = prices.toList(),
            warnings = warnings + assertionIssues,
            periodicRules = periodicRules.toList(),
            autoRules = autoRules.toList(),
            autoApplied = applyAuto,
        )
    }

    // ---------------------------------------------------------------- lines

    private fun handleLine(line: String, lineNo: Int) {
        if (block == Block.COMMENT) {
            if (line.trim() == "end comment") block = Block.NONE
            return
        }
        if (line.isBlank()) {
            finishTransaction()
            finishRule()
            if (block != Block.NONE) block = Block.NONE
            return
        }
        if (line[0] == ' ' || line[0] == '\t') {
            handleIndented(line, lineNo)
            return
        }

        // A non-indented line ends any transaction, rule or directive block.
        finishTransaction()
        finishRule()
        block = Block.NONE

        val c = line[0]
        when {
            c in ";#*%|" -> Unit // comment line
            c.isDigit() -> if (keepTransaction == null || keepTransaction.invoke(lineNo)) startTransaction(line, lineNo)
            else block = Block.SKIP

            c == '~' -> startPeriodicRule(line, lineNo)
            c == '=' -> startAutoRule(line, lineNo)
            else -> handleDirective(line, lineNo)
        }
    }

    private fun handleIndented(line: String, lineNo: Int) {
        val t = line.trim()
        when (block) {
            Block.SKIP -> return
            Block.PERIODIC, Block.AUTO -> {
                if (!t.startsWith(";")) {
                    rulePostings.add(parsePosting(t, lineNo, isRule = block == Block.AUTO))
                    ruleLastLine = lineNo
                }
                return
            }

            Block.ACCOUNT -> {
                if (t.startsWith(";")) accountTypeFromComment(t.substring(1))?.let { accountDecls[blockSubject] = it }
                return
            }

            Block.COMMODITY -> {
                if (t.startsWith("format ")) {
                    commodityDirective(t.removePrefix("format").trim(), lineNo)
                }
                return
            }

            else -> Unit
        }
        val tx = current ?: return // indented text outside a transaction: ignore, like a comment
        tx.lastLine = lineNo
        if (t.startsWith(";")) {
            val text = t.substring(1).trim()
            val lastPosting = tx.postings.lastOrNull()
            if (lastPosting == null) tx.comment = listOf(tx.comment, text).filter { it.isNotEmpty() }.joinToString("\n")
            else lastPosting.comment = listOf(lastPosting.comment, text).filter { it.isNotEmpty() }.joinToString("\n")
            return
        }
        tx.postings.add(parsePosting(t, lineNo))
    }

    private fun handleDirective(line: String, lineNo: Int) {
        val word = line.substringBefore(' ').substringBefore('\t')
        val arg = line.substring(word.length).trim()
        when (word) {
            "account" -> {
                val nameAndComment = splitComment(arg)
                val name = expandAccount(nameAndComment.first.split(Regex("\\s{2,}|\t"))[0].trim())
                if (name.isEmpty()) throw JournalException(lineNo, "account directive needs an account name", line)
                val type = accountTypeFromComment(nameAndComment.second)
                accountDecls[name] = type ?: accountDecls[name]
                block = Block.ACCOUNT
                blockSubject = name
            }

            "commodity" -> {
                val body = splitComment(arg).first.trim()
                if (body.isEmpty()) throw JournalException(lineNo, "commodity directive needs a commodity", line)
                if (body.replace(QUOTED, "").any { it.isDigit() }) commodityDirective(body, lineNo)
                block = Block.COMMODITY
                blockSubject = body
            }

            "decimal-mark" -> {
                val m = arg.trim()
                if (m != "." && m != ",") throw JournalException(lineNo, "decimal-mark should be . or ,", line)
                fileDecimalMark = m[0]
            }

            "D" -> {
                val a = parseAmountText(splitComment(arg).first, lineNo, observe = false)
                    ?: throw JournalException(lineNo, "D directive needs an amount", line)
                defaultCommodity = a.first.commodity
                if (a.first.commodity !in declaredStyles) declaredStyles[a.first.commodity] = a.second
            }

            "Y", "year" -> year = arg.trim().toIntOrNull()
                ?: throw JournalException(lineNo, "could not parse year \"$arg\"", line)

            "apply" -> {
                val rest = arg.trim()
                when {
                    rest.startsWith("account ") -> applyAccounts.add(rest.removePrefix("account ").trim())
                    rest.startsWith("year ") -> year = rest.removePrefix("year ").trim().toIntOrNull() ?: year
                    rest.startsWith("tag") -> Unit
                }
            }

            "end" -> {
                val rest = arg.trim()
                when {
                    rest == "aliases" -> aliases.clear()
                    rest.startsWith("apply account") -> applyAccounts.removeLastOrNull()
                    rest.startsWith("apply") -> Unit
                }
            }

            "alias" -> aliases.add(parseAlias(arg, lineNo))
            "include" -> skippedIncludes++
            "comment" -> block = Block.COMMENT
            "payee" -> payeeName(arg)?.let { payeeDecls.add(it) }
            "P" -> prices.add(parsePrice(arg, lineNo, line))
            "tag", "N", "C", "A", "bucket", "test" -> Unit
            else -> warnings.add(
                JournalIssue(
                    lineNo,
                    "Skipped a line Ledgedroid doesn't understand: \"${line.take(40)}\""
                )
            )
        }
    }

    /**
     * The payee a payee directive declares, like hledger's payeedirectivep: "double quoted text", or the
     * text up to a ; comment, without surrounding spaces.
     */
    private fun payeeName(arg: String): String? {
        val t = arg.trim()
        val name = if (t.startsWith('"')) {
            val close = t.indexOf('"', 1)
            if (close < 0) t.substringBefore(';') else t.substring(1, close)
        } else t.substringBefore(';')
        return name.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * P DATE [TIME] COMMODITY PRICE, like hledger's marketpricedirectivep: P 2026-10-01 AAPL $170.50, or
     * P 2026-10-01 "EUR" 1.08 USD. The commodity may be quoted.
     */
    private fun parsePrice(arg: String, lineNo: Int, line: String): MarketPrice {
        var rest = arg.substringBefore(';').trim()
        val dateText = rest.substringBefore(' ').substringBefore('\t')
        val date = parseDate(dateText, lineNo, line, year)
        rest = rest.drop(dateText.length).trim()
        // An optional time of day, which only orders prices on the same day.
        Regex("^\\d{1,2}:\\d{2}(:\\d{2})?\\s+").find(rest)?.let { rest = rest.drop(it.value.length) }
        val commodity: String
        if (rest.startsWith('"')) {
            val close = rest.indexOf('"', 1)
            if (close < 0) throw JournalException(lineNo, "P: the commodity's closing quote is missing", line)
            commodity = rest.substring(1, close)
            rest = rest.substring(close + 1).trim()
        } else {
            commodity = rest.takeWhile { !it.isWhitespace() }
            rest = rest.drop(commodity.length).trim()
        }
        if (commodity.isEmpty() || rest.isEmpty()) {
            throw JournalException(lineNo, "P needs a date, a commodity and its price, eg P 2026-10-01 AAPL \$170.50", line)
        }
        val (price, _) = parseAmountText(rest, lineNo, observe = true)
            ?: throw JournalException(lineNo, "P: \"$rest\" isn't an amount", line)
        return MarketPrice(date, commodity, Amount(price.commodity, price.quantity), lineNo)
    }

    // ---------------------------------------------------------------- transactions

    private fun startTransaction(line: String, lineNo: Int) {
        val dateToken = line.takeWhile { !it.isWhitespace() && it != ';' }
        val dates = dateToken.split('=')
        val date = parseDate(dates[0], lineNo, line)
        val date2 = dates.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { parseDate(it, lineNo, line, date.year) }

        var rest = line.substring(dateToken.length).trimStart()
        var status = Status.UNMARKED
        if (rest.startsWith("*")) {
            status = Status.CLEARED; rest = rest.substring(1).trimStart()
        } else if (rest.startsWith("!")) {
            status = Status.PENDING; rest = rest.substring(1).trimStart()
        }
        var code = ""
        if (rest.startsWith("(") && rest.contains(')')) {
            code = rest.substring(1, rest.indexOf(')')).trim()
            rest = rest.substring(rest.indexOf(')') + 1).trimStart()
        }
        val (desc, comment) = splitComment(rest)
        current = RawTransaction(txns.size, date, date2, status, code, desc.trim(), comment.trim(), lineNo)
    }

    // ---------------------------------------------------------------- rules

    private fun startPeriodicRule(line: String, lineNo: Int) {
        val body = line.substring(1)
        // The period expression ends at two spaces, a tab, a semicolon or the end of the line.
        val end = Regex("\\s{2,}|\t|;").find(body.trimStart())?.range?.first
        val trimmed = body.trimStart()
        val periodText = (if (end == null) trimmed else trimmed.substring(0, end)).trim()
        var rest = if (end == null) "" else trimmed.substring(end)
        val expr = try {
            parsePeriodExpr(periodText)
        } catch (e: IllegalArgumentException) {
            throw JournalException(lineNo, "${e.message}. A periodic rule looks like: ~ monthly  description", line)
        }
        if (expr.interval == null) {
            throw JournalException(lineNo, "this periodic rule needs an interval, like ~ monthly", line)
        }
        rest = splitComment(rest).first.trim()
        var status = Status.UNMARKED
        if (rest.startsWith("*")) {
            status = Status.CLEARED; rest = rest.substring(1).trim()
        } else if (rest.startsWith("!")) {
            status = Status.PENDING; rest = rest.substring(1).trim()
        }
        ruleHeader = PeriodicHeader(periodText, expr, status, rest, lineNo)
        ruleLastLine = lineNo
        block = Block.PERIODIC
    }

    private fun startAutoRule(line: String, lineNo: Int) {
        val queryText = splitComment(line.substring(1)).first.trim()
        val query = try {
            Query.parse(queryText)
        } catch (e: IllegalArgumentException) {
            throw JournalException(lineNo, "in this auto posting rule's query: ${e.message}", line)
        }
        ruleHeader = AutoHeader(queryText, query, lineNo)
        ruleLastLine = lineNo
        block = Block.AUTO
    }

    private fun finishRule() {
        val header = ruleHeader ?: return
        ruleHeader = null
        val postings =
            rulePostings.map { RulePosting(it.account, it.kind, it.status, it.amount, it.isMultiplier, it.line) }
        rulePostings.clear()
        when (header) {
            is PeriodicHeader -> periodicRules.add(
                PeriodicRule(
                    header.text, header.expr.interval!!, header.expr.span, header.status, header.description,
                    postings, header.line, ruleLastLine,
                )
            )

            is AutoHeader -> {
                postings.firstOrNull { it.amount == null }?.let {
                    throw JournalException(
                        it.line,
                        "auto posting rules need an amount on every posting",
                        lines[it.line - 1]
                    )
                }
                autoRules.add(AutoRule(header.text, header.query, postings, header.line))
            }
        }
    }

    private fun finishTransaction() {
        val tx = current ?: return
        current = null
        txns.add(tx)
    }

    private fun parseDate(s: String, lineNo: Int, line: String, defaultYear: Int? = null): LocalDate {
        val parts = s.split('-', '/', '.')
        try {
            val nums = parts.map { it.toInt() }
            return when (nums.size) {
                3 -> LocalDate.of(nums[0], nums[1], nums[2])
                2 -> LocalDate.of(defaultYear ?: year ?: today.year, nums[0], nums[1])
                else -> throw NumberFormatException()
            }
        } catch (e: NumberFormatException) {
            throw JournalException(lineNo, "could not parse the date \"$s\" (expected YYYY-MM-DD)", line)
        } catch (e: DateTimeException) {
            throw JournalException(lineNo, "invalid date \"$s\": ${e.message}", line)
        }
    }

    private fun parsePosting(text: String, lineNo: Int, isRule: Boolean = false): RawPosting {
        var s = text
        var status = Status.UNMARKED
        if (s.length > 1 && (s[0] == '*' || s[0] == '!') && s[1].isWhitespace()) {
            status = if (s[0] == '*') Status.CLEARED else Status.PENDING
            s = s.substring(1).trimStart()
        }

        val sep = SEPARATOR.find(s)
        var acctPart = if (sep == null) s else s.substring(0, sep.range.first)
        var rest = if (sep == null) "" else s.substring(sep.range.last + 1)
        val semi = acctPart.indexOf(';')
        if (semi >= 0) {
            rest = acctPart.substring(semi) + (if (rest.isNotEmpty()) "  $rest" else "")
            acctPart = acctPart.substring(0, semi)
        }
        acctPart = acctPart.trim()

        var kind = PostingKind.REAL
        if (acctPart.length > 2 && acctPart.startsWith("(") && acctPart.endsWith(")")) {
            kind = PostingKind.VIRTUAL; acctPart = acctPart.substring(1, acctPart.length - 1).trim()
        } else if (acctPart.length > 2 && acctPart.startsWith("[") && acctPart.endsWith("]")) {
            kind = PostingKind.BALANCED_VIRTUAL; acctPart = acctPart.substring(1, acctPart.length - 1).trim()
        }
        val account = expandAccount(acctPart)
        if (account.isEmpty()) throw JournalException(lineNo, "posting has no account name", text)

        val (amountExpr, comment) = splitComment(rest)
        var a = stripLotAnnotations(amountExpr).trim()
        var isMultiplier = false
        if (isRule && a.startsWith("*")) {
            isMultiplier = true
            a = a.substring(1).trim()
        }

        var assertion: BalanceAssertion? = null
        val eq = indexOutsideQuotes(a, '=')
        if (eq >= 0) {
            var t = a.substring(eq + 1)
            a = a.substring(0, eq).trim()
            var total = false
            var inclusive = false
            if (t.startsWith("=")) {
                total = true; t = t.substring(1)
            }
            if (t.startsWith("*")) {
                inclusive = true; t = t.substring(1)
            }
            val amt = parseAmountText(t, lineNo, secondary = true)?.first
                ?: throw JournalException(lineNo, "balance assertion needs an amount", text)
            assertion = BalanceAssertion(amt, total, inclusive, lineNo)
        }

        var cost: Cost? = null
        val at = indexOutsideQuotes(a, '@')
        var amountText = a
        if (at >= 0) {
            var pre = a.substring(0, at)
            var t = a.substring(at + 1)
            var isTotal = false
            if (t.startsWith("@")) {
                isTotal = true; t = t.substring(1)
            }
            if (pre.trimEnd().endsWith("(") && t.startsWith(")")) {
                pre = pre.trimEnd().dropLast(1); t = t.substring(1)
            }
            amountText = pre
            val c = parseAmountText(t, lineNo, secondary = true)?.first
                ?: throw JournalException(lineNo, "cost (@ or @@) needs an amount", text)
            cost = Cost(isTotal, c.commodity, c.quantity)
        }

        // Amounts in rules don't set display styles unless nothing else does; multipliers never do,
        // and don't get the D default commodity (as in hledger).
        val amount =
            parseAmountText(amountText, lineNo, secondary = isRule, observe = !isMultiplier, multiplier = isMultiplier)
                ?.first?.copy(cost = cost)
        if (cost != null && amount == null) throw JournalException(lineNo, "a cost needs an amount before it", text)
        if (isMultiplier && amount == null) throw JournalException(
            lineNo,
            "* should be followed by a number to multiply by",
            text
        )
        return RawPosting(account, kind, status, amount, assertion, comment.trim(), lineNo, isMultiplier)
    }

    // ---------------------------------------------------------------- amounts

    /** Parse an amount, returning it with the style it was written in; null if the text is blank. */
    private fun parseAmountText(
        text: String,
        lineNo: Int,
        secondary: Boolean = false,
        observe: Boolean = true,
        multiplier: Boolean = false,
    ): Pair<Amount, AmountStyle>? {
        if (text.isBlank()) return null
        val lexed = lexAmount(text.trim(), lineNo)
        val commodity = lexed.commodity ?: (if (multiplier) null else defaultCommodity) ?: ""
        val mark = declaredMarks[commodity] ?: fileDecimalMark
        val num = interpretNumber(lexed.number, mark, lineNo)
        val qty = if (lexed.negative) num.value.negate() else num.value
        val defaultStyle =
            if (lexed.commodity == null && defaultCommodity != null && !multiplier) declaredStyles[commodity] else null
        val style = defaultStyle ?: AmountStyle(
            side = lexed.side ?: Side.RIGHT,
            spaced = lexed.spaced,
            decimalMark = num.decimalMark ?: mark ?: '.',
            groupMark = num.groupMark,
            groupSizes = num.groupSizes,
            precision = num.precision,
        )
        if (observe) {
            val obs = if (secondary) secondaryObs else primaryObs
            obs.getOrPut(commodity) {
                ObservedStyle(style.side, style.spaced, num.decimalMark, num.groupMark, num.groupSizes, num.precision)
            }.see(num)
        }
        return Amount(commodity, qty) to style
    }

    private fun lexAmount(s: String, lineNo: Int): LexedAmount {
        var i = 0
        var negative = false
        fun skipWs(): Boolean {
            val start = i
            while (i < s.length && s[i].isWhitespace()) i++
            return i > start
        }

        fun readSign() {
            if (i < s.length && (s[i] == '-' || s[i] == '+')) {
                if (s[i] == '-') negative = !negative
                i++
                skipWs()
            }
        }

        fun readSymbol(): String {
            if (s[i] == '"') {
                val end = s.indexOf('"', i + 1)
                if (end < 0) throw JournalException(lineNo, "unclosed quote in commodity symbol: $s")
                val sym = s.substring(i + 1, end)
                i = end + 1
                return sym
            }
            val start = i
            while (i < s.length && !isNonSimpleCommodityChar(s[i])) i++
            return s.substring(start, i)
        }

        readSign()
        var commodity: String? = null
        var side: Side? = null
        var spaced = false
        if (i < s.length && (s[i] == '"' || !isNonSimpleCommodityChar(s[i]))) {
            commodity = readSymbol()
            side = Side.LEFT
            spaced = skipWs()
            readSign()
        }
        val numStart = i
        while (i < s.length && (s[i].isDigit() || s[i] in NUMBER_PUNCT)) i++
        val number = s.substring(numStart, i)
        if (number.none { it.isDigit() }) throw JournalException(lineNo, "expected a number in the amount \"$s\"")
        if (commodity == null) {
            val ws = skipWs()
            if (i < s.length) {
                val sym = readSymbol()
                if (sym.isNotEmpty()) {
                    commodity = sym; side = Side.RIGHT; spaced = ws
                }
            }
        }
        skipWs()
        if (i < s.length) throw JournalException(lineNo, "unexpected \"${s.substring(i)}\" in the amount \"$s\"")
        return LexedAmount(commodity, side, spaced, negative, number)
    }

    private fun interpretNumber(raw: String, declaredMark: Char?, lineNo: Int): ParsedNumber {
        val marks = raw.filter { it == '.' || it == ',' }
        val decimalMark: Char? = when {
            declaredMark != null -> if (raw.contains(declaredMark)) declaredMark else null
            marks.isEmpty() -> null
            marks.toSet().size == 2 -> marks.last()
            marks.length == 1 -> marks[0] // ambiguous 1,000 / 1.000: hledger assumes a decimal mark
            else -> null // one kind of mark, repeated: digit group marks
        }
        val intRaw: String
        val fracRaw: String
        if (decimalMark != null) {
            val k = raw.lastIndexOf(decimalMark)
            intRaw = raw.substring(0, k); fracRaw = raw.substring(k + 1)
            if (intRaw.contains(decimalMark)) throw JournalException(lineNo, "\"$raw\" has more than one decimal mark")
        } else {
            intRaw = raw; fracRaw = ""
        }
        if (fracRaw.any { !it.isDigit() }) throw JournalException(
            lineNo,
            "\"$raw\": digit group marks can't come after the decimal mark"
        )
        val groupChars = intRaw.filter { !it.isDigit() }.toSet()
        if (groupChars.size > 1) throw JournalException(lineNo, "\"$raw\" mixes different digit group marks")
        val groupMark = groupChars.firstOrNull()
        val groups = if (groupMark != null) intRaw.split(groupMark) else listOf(intRaw)
        if (groupMark != null && groups.any { it.isEmpty() }) throw JournalException(
            lineNo,
            "\"$raw\" has a misplaced digit group mark"
        )
        val sizes = groups.drop(1).reversed().map { it.length }.let { s ->
            var n = s.size
            while (n > 1 && s[n - 1] == s[n - 2]) n--
            s.take(n)
        }
        val intDigits = groups.joinToString("").ifEmpty { "0" }
        val value = BigDecimal(if (fracRaw.isEmpty()) intDigits else "$intDigits.$fracRaw")
        return ParsedNumber(value, decimalMark, groupMark, sizes, fracRaw.length)
    }

    private fun commodityDirective(body: String, lineNo: Int) {
        val lexed = lexAmount(body, lineNo)
        val commodity = lexed.commodity ?: ""
        val num = interpretNumber(lexed.number, fileDecimalMark, lineNo)
        num.decimalMark?.let { declaredMarks[commodity] = it }
        declaredStyles[commodity] = AmountStyle(
            side = lexed.side ?: Side.RIGHT,
            spaced = lexed.spaced,
            decimalMark = num.decimalMark ?: fileDecimalMark ?: '.',
            groupMark = num.groupMark,
            groupSizes = num.groupSizes,
            precision = num.precision,
        )
    }

    private fun buildStyles(): Styles {
        val all = LinkedHashMap<String, AmountStyle>()
        for ((c, o) in secondaryObs) all[c] = o.toStyle()
        for ((c, o) in primaryObs) all[c] = o.toStyle()
        all.putAll(declaredStyles)
        return Styles(all)
    }

    private fun ObservedStyle.toStyle() = AmountStyle(
        side = side,
        spaced = spaced,
        decimalMark = decimalMark ?: fileDecimalMark ?: if (groupMark == '.') ',' else '.',
        groupMark = groupMark,
        groupSizes = groupSizes,
        precision = precision,
    )

    // ---------------------------------------------------------------- accounts

    private fun expandAccount(name: String): String {
        var a = name.trim()
        if (a.isEmpty()) return a
        if (applyAccounts.isNotEmpty()) a = applyAccounts.joinToString(":") + ":" + a
        for (alias in aliases.asReversed()) a = alias(a)
        return a
    }

    private fun parseAlias(arg: String, lineNo: Int): (String) -> String {
        val eq = arg.indexOf('=')
        if (eq < 0) throw JournalException(lineNo, "alias should look like: alias OLD = NEW")
        val old = arg.substring(0, eq).trim()
        val new = arg.substring(eq + 1).trim()
        if (old.length > 1 && old.startsWith("/") && old.endsWith("/")) {
            val re = try {
                Regex(old.substring(1, old.length - 1), RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                throw JournalException(lineNo, "invalid regular expression in alias: ${e.message}")
            }
            val replacement = new.replace(Regex("\\\\(\\d)"), "\\$$1")
            return { a -> re.replace(a, replacement) }
        }
        return { a -> if (isSameOrSubaccount(a, old)) new + a.substring(old.length) else a }
    }

    private fun accountTypeFromComment(comment: String): AccountType? {
        val m = TYPE_TAG.find(comment) ?: return null
        return AccountType.parse(m.groupValues[1])
    }

    private fun summarizeSkipped() {
        if (skippedIncludes > 0) warnings.add(
            JournalIssue(
                0,
                "Skipped $skippedIncludes include directive(s): included files are only read once the journal's " +
                        "folder is chosen, in Settings › Included files."
            )
        )
    }

    companion object {
        private val SEPARATOR = Regex("\t| {2,}")
        private val QUOTED = Regex("\"[^\"]*\"")
        private const val NUMBER_PUNCT = ".,_'\u00A0\u202F\u2009"
        private val TYPE_TAG = Regex("(?:^|[\\s,])type:\\s*([A-Za-z]+)")
    }
}

/**
 * The lines of a text (split at \n, \r\n or \r, like [String.lines]), found with [String.indexOf],
 * which Android runs as native code; a character-by-character loop in app code can be many times
 * slower on older Android versions. Each line is copied out only when asked for.
 */
internal class LineIndex(private val text: String) : AbstractList<String>() {
    private val starts: IntArray
    private val ends: IntArray

    init {
        val s = IntArrayBuilder(text.length / 40 + 16)
        val e = IntArrayBuilder(text.length / 40 + 16)
        if (text.indexOf('\r') < 0) {
            // The usual case: \n only.
            var from = 0
            while (true) {
                val nl = text.indexOf('\n', from)
                s.add(from)
                if (nl < 0) {
                    e.add(text.length); break
                }
                e.add(nl)
                from = nl + 1
            }
        } else {
            var from = 0
            while (true) {
                val nl = text.indexOf('\n', from)
                val cr = text.indexOf('\r', from)
                val end = if (nl < 0) cr else if (cr < 0) nl else minOf(nl, cr)
                s.add(from)
                if (end < 0) {
                    e.add(text.length); break
                }
                e.add(end)
                from = if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
            }
        }
        starts = s.toArray()
        ends = e.toArray()
    }

    override val size: Int get() = starts.size

    override fun get(index: Int): String = text.substring(starts[index], ends[index])

    /** True if the line starts with a space or tab and has some text, without copying it. */
    fun isIndentedText(index: Int): Boolean {
        val s = starts[index]
        val e = ends[index]
        if (s >= e || (text[s] != ' ' && text[s] != '\t')) return false
        for (k in s + 1 until e) if (!text[k].isWhitespace()) return true
        return false
    }
}

private class IntArrayBuilder(capacity: Int) {
    private var a = IntArray(capacity)
    private var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }

    fun toArray(): IntArray = a.copyOf(n)
}

/** The lines of [text], like [String.lines]. */
internal fun splitLines(text: String): List<String> = LineIndex(text)

/** How many lines [text] has, counting with [String.indexOf]. */
internal fun countNewlines(text: String): Int {
    var count = 0
    var from = text.indexOf('\n')
    while (from >= 0) {
        count++
        from = text.indexOf('\n', from + 1)
    }
    return count
}

/** Split "text ; comment" at the first semicolon that isn't inside double quotes. */
internal fun splitComment(s: String): Pair<String, String> {
    val i = indexOutsideQuotes(s, ';')
    return if (i < 0) s to "" else s.substring(0, i) to s.substring(i + 1)
}

internal fun indexOutsideQuotes(s: String, ch: Char): Int {
    var inQuotes = false
    for ((i, c) in s.withIndex()) {
        if (c == '"') inQuotes = !inQuotes
        else if (c == ch && !inQuotes) return i
    }
    return -1
}

/** Remove lot annotations like {$50} {2024-01-01, $50} [2024-01-01] which Ledgedroid ignores. */
internal fun stripLotAnnotations(s: String): String {
    val out = StringBuilder()
    var inQuotes = false
    var depth = 0
    var close = ' '
    for (c in s) {
        if (depth == 0 && c == '"') inQuotes = !inQuotes
        if (!inQuotes && depth == 0 && (c == '{' || c == '[')) {
            depth = 1; close = if (c == '{') '}' else ']'; continue
        }
        if (depth > 0) {
            if (c == close) depth = 0; continue
        }
        out.append(c)
    }
    return out.toString()
}
