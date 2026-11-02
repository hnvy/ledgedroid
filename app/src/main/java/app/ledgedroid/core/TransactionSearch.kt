package app.ledgedroid.core

/**
 * Searching all transactions, newest first. The search text is a [Query], so every search box term
 * works (desc:, payee:, acct:, amt:, date:, tag:, status:, cur:, not:). Plain words, which in reports
 * mean account names, match more widely here: a transaction matches a plain word if its description,
 * an account, a comment or an amount contains it. So "cafe" finds Corner Cafe, and "4.50" finds amounts.
 */
object TransactionSearch {

    data class Result(val transactions: List<Transaction>, val total: Int, val error: String?)

    fun search(journal: Journal, text: String, styles: Styles = journal.styles, limit: Int = 500): Result {
        val (words, queryText) = splitWords(text)
        val query = try {
            Query.parse(queryText)
        } catch (e: IllegalArgumentException) {
            return Result(emptyList(), 0, e.message)
        }
        val needles = words.map { it.lowercase() }
        val out = ArrayList<Transaction>()
        var total = 0
        for (t in journal.transactions.asReversed()) {
            if (!query.matchesTransaction(t)) continue
            if (needles.isNotEmpty() && !needles.all { n -> contains(t, n, styles) }) continue
            total++
            if (out.size < limit) out.add(t)
        }
        return Result(out, total, null)
    }

    /** Plain words (no prefix, not negated) are searched broadly; everything else goes to the query. */
    private fun splitWords(text: String): Pair<List<String>, String> {
        val words = ArrayList<String>()
        val rest = ArrayList<String>()
        for (token in Query.tokenize(text)) {
            val hasPrefix =
                Regex("^(not|acct|desc|payee|cur|status|depth|date|date2|amt|tag|code|note|real|type|expr|any|all):")
                    .containsMatchIn(token)
            if (hasPrefix) rest.add(if (token.any { it.isWhitespace() }) "\"$token\"" else token) else words.add(token)
        }
        return words to rest.joinToString(" ")
    }

    private fun contains(t: Transaction, needle: String, styles: Styles): Boolean {
        if (t.description.lowercase().contains(needle)) return true
        if (t.comment.lowercase().contains(needle)) return true
        if (t.code.lowercase().contains(needle)) return true
        for (p in t.postings) {
            if (p.account.lowercase().contains(needle)) return true
            if (p.comment.lowercase().contains(needle)) return true
            val a = p.amount
            if (a.quantity.abs().toPlainString().contains(needle)) return true
            if (styles.format(a.commodity, a.quantity).lowercase().contains(needle)) return true
        }
        return false
    }
}
