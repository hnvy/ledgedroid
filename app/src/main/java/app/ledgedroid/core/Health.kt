package app.ledgedroid.core

/** What a journal health issue is about, and so how it's fixed. */
enum class HealthKind(val title: String) {
    UNDECLARED_ACCOUNT("Accounts used but not declared"),
    UNDECLARED_PAYEE("Payees not declared"),
    DATE_OUT_OF_ORDER("Dates out of order"),
    FAILED_ASSERTION("Failed balance assertions"),
}

/**
 * One thing hledger check would report. [name] is the account or payee to declare; [transactionIndex]
 * the transaction to open (with its [file] and [line]); [count] how many postings or transactions it
 * concerns.
 */
data class HealthIssue(
    val kind: HealthKind,
    val message: String,
    val name: String? = null,
    val transactionIndex: Int? = null,
    val file: String? = null,
    val line: Int = 0,
    val count: Int = 1,
)

/**
 * Journal health, like hledger check: accounts (every account used is declared), payees (every payee
 * is declared, once any are, as with the Payees screen's Declared view), ordereddates (transactions are
 * in date order within each file), and assertions (balance assertions hold).
 */
object Health {
    fun check(journal: Journal): List<HealthIssue> = buildList {
        val real = journal.transactions.filter { !it.forecast }

        // accounts: like hledger check accounts, each account used must be declared itself.
        val declared = journal.declaredAccounts.toHashSet()
        val uses = LinkedHashMap<String, Int>()
        for (t in real) for (p in t.postings) if (!p.generated) uses.merge(p.account, 1, Int::plus)
        for ((a, n) in uses.entries.sortedBy { it.key }) if (a !in declared) {
            add(HealthIssue(HealthKind.UNDECLARED_ACCOUNT, "$a, used ${times(n)}", name = a, count = n))
        }

        // payees: only once the journal declares some, as hledger check payees is opt-in.
        if (journal.declaredPayees.isNotEmpty()) {
            val payees = LinkedHashMap<String, Int>()
            for (t in real) Payees.of(t.description).takeIf { it.isNotEmpty() }?.let { payees.merge(it, 1, Int::plus) }
            for ((p, n) in payees.entries.sortedBy { it.key.lowercase() }) if (!journal.isDeclaredPayee(p)) {
                add(HealthIssue(HealthKind.UNDECLARED_PAYEE, "$p, in ${n} transaction${if (n == 1) "" else "s"}", name = p, count = n))
            }
        }

        // ordereddates: within each file, in the order written, no transaction dated before the one above.
        for ((file, ts) in real.groupBy { it.file }) {
            var latest: Transaction? = null
            for (t in ts.sortedBy { it.line }) {
                val l = latest
                if (l != null && t.date.isBefore(l.date)) {
                    add(HealthIssue(HealthKind.DATE_OUT_OF_ORDER,
                        "${t.date} ${t.description.ifEmpty { "(no description)" }}, after ${l.date}" + where(file, t.line),
                        transactionIndex = t.index, file = file, line = t.line))
                } else latest = t
            }
        }

        // assertions: the balance assertions that failed when the journal was read.
        for (w in journal.warnings.filter { it.isFailedAssertion }) {
            val t = real.firstOrNull { tx -> tx.file == w.file && w.line in tx.line..tx.lastLine }
            add(HealthIssue(HealthKind.FAILED_ASSERTION, w.message + where(w.file, w.line),
                transactionIndex = t?.index, file = w.file, line = w.line))
        }
    }

    private fun times(n: Int) = if (n == 1) "once" else "$n times"
    private fun where(file: String?, line: Int) = " (" + (file?.let { "$it, " } ?: "") + "line $line)"
}
