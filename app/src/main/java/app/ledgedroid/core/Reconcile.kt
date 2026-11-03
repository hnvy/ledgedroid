package app.ledgedroid.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * One posting to reconcile. [cleared] is its status now (its own mark, or its transaction's). [locked]
 * says why it can't be changed here, if so: it's in an included file, or it's cleared by its
 * transaction's mark, which a posting can't undo.
 */
data class ReconcileItem(
    val transaction: Transaction,
    val posting: Posting,
    val quantity: BigDecimal,
    val cleared: Boolean,
    val locked: String? = null,
    /** The posting's place in its transaction. */
    val postingIndex: Int = 0,
) {
    /** Unique among the items: the transaction's place in the journal and the posting's in it. */
    val key: Int get() = transaction.index * 1000 + postingIndex
}

/**
 * Reconciling [account] (and its subaccounts, with [subaccounts]) in [commodity] against a statement
 * dated [statementDate]: its postings up to that date, which are cleared, and the cleared balance to
 * compare with the statement's closing balance. Ticking postings off clears them; the difference shows
 * what's still unaccounted for.
 */
class Reconcile(
    private val journal: Journal,
    val account: String,
    val subaccounts: Boolean,
    val commodity: String,
    val statementDate: LocalDate,
) {
    /** The postings, oldest first (journal order), with an amount in [commodity]. */
    val items: List<ReconcileItem> = buildList {
        for (t in journal.transactions) {
            if (t.forecast || t.date.isAfter(statementDate)) continue
            for ((pi, p) in t.postings.withIndex()) {
                if (p.generated || p.kind == PostingKind.VIRTUAL) continue
                if (p.account != account && !(subaccounts && p.account.startsWith("$account:"))) continue
                if (p.amount.commodity != commodity) continue
                val effective = if (p.status != Status.UNMARKED) p.status else t.status
                val cleared = effective == Status.CLEARED
                val locked = when {
                    t.file != null -> "In ${t.file}: change it there"
                    cleared && p.status != Status.CLEARED -> "Cleared on the whole transaction"
                    else -> null
                }
                add(ReconcileItem(t, p, p.amount.quantity, cleared, locked, pi))
            }
        }
    }

    /** What's ticked to start with: what's cleared already. */
    val initiallyTicked: Set<Int> get() = items.filter { it.cleared }.map { it.key }.toSet()

    /** The balance of the ticked postings. */
    fun clearedBalance(ticked: Set<Int>): BigDecimal =
        items.filter { it.key in ticked }.fold(BigDecimal.ZERO) { a, i -> a + i.quantity }

    /** The statement's balance less the cleared balance: zero when it all adds up. */
    fun difference(statement: BigDecimal, ticked: Set<Int>): BigDecimal = statement - clearedBalance(ticked)

    /** The posting lines whose mark changes: newly ticked ones get *, unticked ones lose theirs. */
    fun changes(ticked: Set<Int>): Pair<List<Int>, List<Int>> {
        val toClear = items.filter { it.locked == null && !it.cleared && it.key in ticked }.map { it.posting.line }
        val toUnclear = items.filter { it.locked == null && it.cleared && it.key !in ticked }.map { it.posting.line }
        return toClear to toUnclear
    }

    /**
     * Where a balance assertion for the statement can go, if it can: the last ticked posting, which must
     * be in the main file, have an amount written and no assertion yet; every posting to the account
     * before it must be ticked (hledger checks the whole balance, cleared or not); and it must be a single
     * account, since = doesn't include subaccounts. Otherwise null, with [assertionProblem] saying why.
     */
    fun assertionTarget(ticked: Set<Int>): ReconcileItem? = assertionCheck(ticked).first

    fun assertionProblem(ticked: Set<Int>): String? = assertionCheck(ticked).second

    private fun assertionCheck(ticked: Set<Int>): Pair<ReconcileItem?, String?> {
        if (items.map { it.posting.account }.distinct().size > 1) {
            return null to "It covers subaccounts too, and a balance assertion is about one account"
        }
        val last = items.lastOrNull { it.key in ticked } ?: return null to "Nothing is cleared yet"
        val before = items.takeWhile { it !== last }
        val open = before.count { it.key !in ticked }
        return when {
            open > 0 -> null to "$open posting${if (open == 1) "" else "s"} before the last cleared one ${if (open == 1) "isn't" else "aren't"} cleared, and hledger checks the whole balance"
            last.transaction.file != null -> null to "The last cleared posting is in ${last.transaction.file}"
            last.posting.inferred -> null to "The last cleared posting has no amount written, so = would set it rather than check it"
            last.posting.assertion != null -> null to "The last cleared posting already has a balance assertion"
            else -> last to null
        }
    }

    companion object {
        /** The commodities [account]'s postings are in, most used first, for choosing one. */
        fun commodities(journal: Journal, account: String, subaccounts: Boolean): List<String> =
            journal.transactions.asSequence().filter { !it.forecast }.flatMap { it.postings }
                .filter { it.account == account || (subaccounts && it.account.startsWith("$account:")) }
                .groupingBy { it.amount.commodity }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
}
