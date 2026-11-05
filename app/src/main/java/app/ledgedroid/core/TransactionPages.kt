package app.ledgedroid.core

/** The order to list all transactions in. */
enum class TransactionOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    FILE("As in the file"),
}

/**
 * A page of transactions: [transactions], and [next], where the next page starts in the ordered list
 * (null when there are no more).
 */
data class TransactionPage(val transactions: List<Transaction>, val next: Int?)

/**
 * All the journal's transactions, a page at a time, so a big journal needn't be gone through or shown all at
 * once. Each page carries on from where the last stopped, so however many pages are loaded, each
 * transaction is looked at once at most.
 */
object TransactionPages {
    /** The journal's transactions (not --forecast ones) in [order]. Newest and oldest first cost nothing. */
    fun ordered(journal: Journal, order: TransactionOrder): List<Transaction> {
        val real = if (journal.transactions.any { it.forecast }) journal.transactions.filter { !it.forecast } else journal.transactions
        return when (order) {
            TransactionOrder.NEWEST -> real.asReversed()
            TransactionOrder.OLDEST -> real
            // The order written: the main file's, with included files' where they're included.
            TransactionOrder.FILE -> real.sortedBy { it.index }
        }
    }

    /**
     * Up to [count] transactions of [list] matching [match] (all, with null), from position [from]. Looks
     * ahead only as far as the next match, which is where the following page starts: pass [fromMatches]
     * when continuing from a page's [TransactionPage.next], which is known to match, so it isn't tested
     * again.
     */
    fun page(list: List<Transaction>, match: ((Transaction) -> Boolean)?, from: Int, count: Int, fromMatches: Boolean = false): TransactionPage {
        val out = ArrayList<Transaction>(minOf(count, maxOf(0, list.size - from)))
        var i = from
        while (i < list.size && out.size < count) {
            val t = list[i]
            if (match == null || (fromMatches && i == from) || match(t)) out.add(t)
            i++
        }
        if (match != null) while (i < list.size && !match(list[i])) i++
        return TransactionPage(out, if (i < list.size) i else null)
    }
}
