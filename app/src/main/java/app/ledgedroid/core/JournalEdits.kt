package app.ledgedroid.core

import java.math.BigDecimal

/**
 * One line changed by an [EditPlan]: line [line] (1-based) of the main journal file goes from [before]
 * to [after]. With [before] null, [after] is a new line inserted after line [line] (0: at the top).
 */
data class LineChange(val line: Int, val before: String?, val after: String)

/**
 * Changes to make to the journal, line by line, to show before making them: [changes], and [notChanged],
 * things that match but can't be changed here, each saying why (in an included file, or written
 * differently in the file, through an alias or apply account).
 */
data class EditPlan(val what: String, val changes: List<LineChange>, val notChanged: List<String> = emptyList()) {
    val isEmpty: Boolean get() = changes.isEmpty()

    /**
     * [text] with the changes made. Each changed line must still read as [LineChange.before], so a journal
     * changed elsewhere since the plan was made isn't overwritten; otherwise [EntryException].
     */
    fun apply(text: String): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = text.endsWith("\n")
        val lines = text.removeSuffix("\n").removeSuffix("\r").split(nl).toMutableList()
        if (text.isEmpty()) lines.clear()
        for (c in changes.filter { it.before != null }) {
            val i = c.line - 1
            if (i !in lines.indices || lines[i] != c.before) {
                throw EntryException("The journal has changed since this was worked out (line ${c.line}). Look again, then try again.")
            }
            lines[i] = c.after
        }
        // Insertions from the bottom up, so line numbers stay right; several after one line keep their order.
        for ((line, group) in changes.filter { it.before == null }.groupBy { it.line }.toSortedMap(reverseOrder())) {
            if (line < 0 || line > lines.size) throw EntryException("The journal has changed since this was worked out.")
            lines.addAll(line, group.map { it.after })
        }
        return lines.joinToString(nl) + if (endsWithNewline || text.isEmpty()) nl else ""
    }
}

/**
 * Changes to the journal's text that touch many lines: renaming an account, moving a payee's postings to
 * another account, clearing postings, adding a balance assertion, declaring accounts and payees. Only the
 * main journal file is changed; matches in included files are listed in [EditPlan.notChanged].
 */
object JournalEdits {

    /** A posting line split up: indent, status mark, the account as written (with any brackets), the rest. */
    internal data class PostingLine(
        val indent: String,
        val status: Char?,
        val account: String,
        val open: String,
        val close: String,
        val rest: String,
    ) {
        fun render(status: Char? = this.status, account: String = this.account, rest: String = this.rest): String =
            indent + (status?.let { "$it " } ?: "") + open + account + close + rest
    }

    private val SEPARATOR = Regex("\t| {2,}")

    internal fun parsePostingLine(line: String): PostingLine? {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        if (indent.isEmpty()) return null
        var s = line.substring(indent.length)
        if (s.isEmpty() || s[0] == ';' || s[0] == '#') return null
        var status: Char? = null
        if (s[0] == '*' || s[0] == '!') {
            status = s[0]
            s = s.substring(1).trimStart(' ', '\t')
        }
        val sep = SEPARATOR.find(s)
        var accountPart = if (sep != null) s.substring(0, sep.range.first) else s
        var rest = if (sep != null) s.substring(sep.range.first) else ""
        // A comment right after the account, with one space: "assets:cash ;note".
        val semi = accountPart.indexOf(';')
        if (semi >= 0) {
            rest = accountPart.substring(semi).let { " $it" } + rest
            accountPart = accountPart.substring(0, semi)
        }
        accountPart = accountPart.trimEnd()
        val (open, close) = when {
            accountPart.startsWith("(") && accountPart.endsWith(")") -> "(" to ")"
            accountPart.startsWith("[") && accountPart.endsWith("]") -> "[" to "]"
            else -> "" to ""
        }
        val account = accountPart.removePrefix(open).removeSuffix(close)
        if (account.isEmpty()) return null
        return PostingLine(indent, status, account, open, close, rest)
    }

    /** The posting lines of the main file's transactions and rules, as (line number, account as parsed). */
    private fun postingLines(journal: Journal): List<Pair<Int, String>> = buildList {
        for (t in journal.transactions) {
            if (t.forecast || t.file != null) continue
            for (p in t.postings) if (p.line > 0 && !p.generated) add(p.line to p.account)
        }
        for (r in journal.periodicRules) if (r.file == null) for (p in r.postings) add(p.line to p.account)
        for (r in journal.autoRules) if (r.file == null) for (p in r.postings) add(p.line to p.account)
    }.distinctBy { it.first }

    private fun matchesAccount(account: String, name: String, subaccounts: Boolean) =
        account == name || (subaccounts && account.startsWith("$name:"))

    private fun renamed(account: String, from: String, to: String) = to + account.removePrefix(from)

    /**
     * Rename [from] to [to] (and its subaccounts, with [subaccounts]) everywhere in the main file: postings
     * in transactions and in periodic and auto posting rules, and account directives.
     */
    fun renameAccount(text: String, journal: Journal, from: String, to: String, subaccounts: Boolean = true): EditPlan {
        val lines = splitLines(text)
        val changes = ArrayList<LineChange>()
        val notChanged = ArrayList<String>()
        for ((n, parsed) in postingLines(journal)) {
            if (!matchesAccount(parsed, from, subaccounts)) continue
            val line = lines.getOrNull(n - 1) ?: continue
            val pl = parsePostingLine(line)
            if (pl == null || pl.account != parsed) {
                notChanged.add("Line $n: written as \"${pl?.account ?: line.trim()}\" in the file (an alias or apply account), so it's left")
                continue
            }
            changes.add(LineChange(n, line, pl.render(account = renamed(pl.account, from, to))))
        }
        for ((i, line) in lines.withIndex()) {
            val m = Regex("^account\\s+(.+?)(\\s{2,}.*|\\s*;.*)?$").matchEntire(line) ?: continue
            val name = m.groupValues[1].trim()
            if (!matchesAccount(name, from, subaccounts)) continue
            changes.add(LineChange(i + 1, line, "account " + renamed(name, from, to) + m.groupValues[2]))
        }
        includedMatches(journal) { t -> t.postings.any { matchesAccount(it.account, from, subaccounts) } }?.let { notChanged.add(it) }
        return EditPlan("Rename $from to $to", changes.sortedBy { it.line }, notChanged)
    }

    /** Move the postings to [from] in every transaction with payee [payee] to account [to]. */
    fun recategorise(text: String, journal: Journal, payee: String, from: String, to: String): EditPlan {
        val lines = splitLines(text)
        val changes = ArrayList<LineChange>()
        val notChanged = ArrayList<String>()
        for (t in journal.transactions) {
            if (t.forecast || t.file != null || Payees.of(t.description) != payee) continue
            for (p in t.postings) {
                if (p.account != from || p.line <= 0 || p.generated) continue
                val line = lines.getOrNull(p.line - 1) ?: continue
                val pl = parsePostingLine(line)
                if (pl == null || pl.account != p.account) {
                    notChanged.add("Line ${p.line}: written as \"${pl?.account ?: line.trim()}\" in the file, so it's left"); continue
                }
                changes.add(LineChange(p.line, line, pl.render(account = to)))
            }
        }
        includedMatches(journal) { t -> Payees.of(t.description) == payee && t.postings.any { it.account == from } }?.let { notChanged.add(it) }
        return EditPlan("Move $payee's $from postings to $to", changes, notChanged)
    }

    /** Set the status mark of each posting line in [lines] (cleared, pending or none). */
    fun setPostingStatus(text: String, postingLines: Collection<Int>, status: Status, what: String): EditPlan {
        val lines = splitLines(text)
        val mark = when (status) {
            Status.CLEARED -> '*'
            Status.PENDING -> '!'
            Status.UNMARKED -> null
        }
        val changes = postingLines.sorted().mapNotNull { n ->
            val line = lines.getOrNull(n - 1) ?: return@mapNotNull null
            val pl = parsePostingLine(line) ?: return@mapNotNull null
            if (pl.status == mark) null else LineChange(n, line, pl.render(status = mark))
        }
        return EditPlan(what, changes)
    }

    /**
     * Add a balance assertion "= [amount]" to the posting on line [line], after its amount (and cost),
     * before any comment. The posting must have an amount written: without one, = would make it an
     * assignment.
     */
    fun addAssertion(text: String, line: Int, commodity: String, quantity: BigDecimal, styles: Styles): EditPlan {
        val l = splitLines(text).getOrNull(line - 1) ?: throw EntryException("Line $line isn't in the journal")
        val pl = parsePostingLine(l) ?: throw EntryException("Line $line isn't a posting")
        val body = pl.rest.substringBefore(';')
        val comment = pl.rest.substring(body.length)
        if (body.isBlank()) throw EntryException("The posting on line $line has no amount, so an assertion would set it")
        if (body.contains('=')) throw EntryException("The posting on line $line already has a balance assertion")
        val assertion = "= " + Entry.formatExact(commodity, quantity, styles)
        val newRest = body.trimEnd() + " " + assertion + (if (comment.isNotEmpty()) "  " + comment else "")
        return EditPlan("Add the balance assertion $assertion", listOf(LineChange(line, l, pl.render(rest = newRest))))
    }

    /**
     * Declare [accounts] and [payees] with account and payee directives: after the last directive of the
     * same kind, else after the other kind, else at the top, after any opening comment lines.
     */
    fun declare(text: String, accounts: List<String>, payees: List<String>): EditPlan {
        val lines = splitLines(text)
        val lastAccount = lines.indexOfLast { it.startsWith("account ") } + 1
        val lastPayee = lines.indexOfLast { it.startsWith("payee ") } + 1
        val top = lines.indexOfFirst { it.isNotBlank() && !it.startsWith(";") && !it.startsWith("#") }.let { if (it < 0) lines.size else it }
        val changes = ArrayList<LineChange>()
        val accountsAt = if (lastAccount > 0) lastAccount else if (lastPayee > 0) lastPayee else top
        accounts.forEach { changes.add(LineChange(accountsAt, null, "account $it")) }
        val payeesAt = if (lastPayee > 0) lastPayee else accountsAt
        Payees.directives(payees).lines().filter { it.isNotBlank() }.forEach { changes.add(LineChange(payeesAt, null, it)) }
        // Keep a blank line between new declarations at the top and what follows.
        if (accountsAt == top && lastAccount == 0 && lastPayee == 0 && changes.isNotEmpty() && lines.getOrNull(top)?.isNotBlank() == true) {
            changes.add(LineChange(top, null, ""))
        }
        val what = listOfNotNull(
            accounts.size.takeIf { it > 0 }?.let { if (it == 1) "Declare ${accounts.single()}" else "Declare $it accounts" },
            payees.size.takeIf { it > 0 }?.let { if (it == 1) "declare ${payees.single()}" else "declare $it payees" },
        ).joinToString(", and ").replaceFirstChar { it.uppercase() }
        return EditPlan(what, changes)
    }

    private fun includedMatches(journal: Journal, match: (Transaction) -> Boolean): String? {
        val n = journal.transactions.count { it.file != null && !it.forecast && match(it) }
        if (n == 0) return null
        val files = journal.transactions.filter { it.file != null && !it.forecast && match(it) }.mapNotNull { it.file }.distinct()
        return "$n in included files (${files.joinToString()}), which are only read here: change them there"
    }
}
