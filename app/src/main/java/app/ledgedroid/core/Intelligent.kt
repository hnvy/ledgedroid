package app.ledgedroid.core

import java.math.BigDecimal

/**
 * Intelligent mode (Settings › Adding transactions), after hledger-iadd's suggestions:
 *
 *  - Matching: every word typed must be in the suggestion, and an account can also be matched by the
 *    start of each of its parts, so "e:f:c" or "ex fo co" finds expenses:food:coffee (iadd's fuzzy
 *    matching); best: those used most.
 *  - The similar transaction: once there's a description, the most recent transaction with the same one
 *    (or, failing that, the same payee) is followed: its next account is suggested, and its amount for each
 *    account while the new transaction still follows it; otherwise the amount that balances.
 *  - Once the postings balance, no account is suggested, so Enter finishes.
 *  - A transaction just like one already in the journal is pointed out before it's added.
 */
object Intelligent {

    /**
     * Whether [candidate] matches [query]: all of the query's words appear in it (ignoring case), or, for
     * an account, they match the starts of its parts in order, as in iadd's fuzzy matching.
     */
    fun matches(query: String, candidate: String, fuzzy: Boolean = true): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val c = candidate.lowercase()
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.all { c.contains(it) }) return true
        // The starts-of-parts matching is the costly part: without it, only words anywhere.
        if (!fuzzy || ':' !in c) return false
        val parts = c.split(':')
        return words.all { fuzzy(it, parts) }
    }

    /**
     * iadd's fuzzyMatch: [query]'s letters are taken from the starts of [parts], in order, a part at a time;
     * ':' in the query moves to the next part. "e:f:c", "efc" and "exfoco" all match expenses:food:coffee.
     */
    fun fuzzy(query: String, parts: List<String>): Boolean {
        if (query.isEmpty()) return true
        if (parts.isEmpty()) return false
        val c = query[0]
        if (c == ':') return fuzzy(query.substring(1), parts.drop(1))
        // Skip this part, or take the query's first letter from its start.
        if (fuzzy(query, parts.drop(1))) return true
        val part = parts[0]
        return part.isNotEmpty() && part[0] == c && fuzzy(query.substring(1), listOf(part.substring(1)) + parts.drop(1))
    }

    /** Descriptions in the journal, most used first, then most recent. */
    fun descriptions(journal: Journal): List<String> {
        val uses = LinkedHashMap<String, Int>()
        for (t in journal.transactions.asReversed()) if (!t.forecast && t.description.isNotBlank()) uses.merge(t.description, 1, Int::plus)
        return uses.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * The transaction to follow for [description]: the most recent with the same description (ignoring
     * case), else with the same payee (the part before |).
     */
    fun similar(journal: Journal, description: String): Transaction? {
        val d = description.trim()
        if (d.isEmpty()) return null
        val real = journal.transactions.filter { !it.forecast }
        return real.filter { it.description.equals(d, ignoreCase = true) }.maxByOrNull { it.date }
            ?: Payees.of(d).let { p -> real.filter { Payees.of(it.description).equals(p, ignoreCase = true) }.maxByOrNull { it.date } }
    }

    /** The postings of [t] to follow: those written in the journal (not from auto posting rules). */
    private fun postingsOf(t: Transaction) = t.postings.filter { !it.generated }

    /** The next account of [reference] not entered yet, as iadd's suggestNextPosting. */
    fun nextAccount(reference: Transaction, entered: List<DraftPosting>): String? {
        val used = entered.map { it.account }.toMutableList()
        return postingsOf(reference).firstOrNull { p -> !used.remove(p.account) }?.account
    }

    /** Whether the [entered] postings are among [reference]'s, account and amount (iadd's isSubsetTransaction). */
    fun follows(reference: Transaction, entered: List<DraftPosting>): Boolean {
        val left = postingsOf(reference).toMutableList()
        for (e in entered) {
            val q = Entry.parseAmount(e.amount) ?: return false
            val i = left.indexOfFirst { it.account == e.account && it.amount.commodity == e.commodity.trim() && it.amount.quantity.compareTo(q) == 0 }
            if (i < 0) return false
            left.removeAt(i)
        }
        return true
    }

    /** [reference]'s amount for [account], as (quantity, commodity), or null. */
    fun amountFor(reference: Transaction, account: String): Pair<BigDecimal, String>? =
        postingsOf(reference).firstOrNull { it.account == account }?.let { it.amount.quantity to it.amount.commodity }

    /** [reference]'s price for [account]'s amount, if it had one. */
    fun costFor(reference: Transaction, account: String): Cost? = postingsOf(reference).firstOrNull { it.account == account }?.amount?.cost

    /** Whether [entered] postings balance, at cost where they have a price (and there are at least two). */
    fun balanced(entered: List<DraftPosting>): Boolean {
        if (entered.size < 2) return false
        val sums = HashMap<String, BigDecimal>()
        for (p in entered) {
            val q = Entry.parseAmount(p.amount) ?: return false
            val cost = if (p.cost.isBlank()) null else Entry.parseCost(p.cost).first ?: return false
            val (c, v) = Amount(p.commodity.trim(), q, cost).balancingValue()
            sums.merge(c, v, BigDecimal::add)
        }
        return sums.values.all { it.signum() == 0 }
    }

    /**
     * A transaction already in the journal just like [draft]: same date, description and postings
     * (accounts and amounts, in any order), as iadd's isDuplicateTransaction. Null if there's none.
     */
    fun duplicateOf(journal: Journal, draft: TransactionDraft, styles: Styles): Transaction? {
        fun key(account: String, commodity: String, q: BigDecimal) = Triple(account, commodity, q.stripTrailingZeros())
        val mine = Entry.resolve(draft, styles).mapNotNull { p -> p.quantity?.let { key(p.account, p.commodity, it) } }
            .groupingBy { it }.eachCount()
        return journal.transactions.firstOrNull { t ->
            !t.forecast && t.date == draft.date && t.description == draft.description &&
                    postingsOf(t).map { key(it.account, it.amount.commodity, it.amount.quantity) }.groupingBy { it }.eachCount() == mine
        }
    }

    /** "text ; comment" split into the text and the comment ("" if none), as iadd's comments. */
    fun splitComment(answer: String): Pair<String, String> {
        val m = Regex("(^|\\s);").find(answer) ?: return answer.trim() to ""
        return answer.substring(0, m.range.first).trim() to answer.substring(m.range.last + 1).trim()
    }
}
