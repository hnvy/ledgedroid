package app.ledgedroid.core

import java.time.LocalDate

/**
 * One payee's transactions in a period. spent is what went to expense accounts; received is what
 * came from revenue accounts, as a positive amount.
 */
data class PayeeSummary(
    val name: String,
    val transactions: Int,
    val lastDate: LocalDate,
    val spent: MixedAmount,
    val received: MixedAmount,
    /** Declared with a payee directive. */
    val declared: Boolean = true,
)

object Payees {

    /** The payee of a description: the part before |, like hledger's payee: query. */
    fun of(description: String): String = description.substringBefore('|').trim()

    /**
     * Every payee with transactions in [span], most recent first. With [declaredOnly], only payees
     * declared with a payee directive, and with [undeclaredOnly] only the others; the rest of the
     * transactions aren't added up.
     */
    fun summaries(
        journal: Journal,
        span: DateSpan,
        declaredOnly: Boolean = false,
        undeclaredOnly: Boolean = false,
    ): List<PayeeSummary> {
        class Acc(var count: Int, var last: LocalDate, var spent: MixedAmount, var received: MixedAmount)

        val byPayee = LinkedHashMap<String, Acc>()
        for (t in journal.transactions.asReversed()) {
            if (!span.contains(t.date)) continue
            val payee = of(t.description)
            if (declaredOnly || undeclaredOnly) {
                val declared = journal.isDeclaredPayee(payee)
                if (declaredOnly && !declared || undeclaredOnly && declared) continue
            }
            val acc = byPayee.getOrPut(payee) { Acc(0, t.date, MixedAmount.ZERO, MixedAmount.ZERO) }
            acc.count++
            for (p in t.postings) {
                when (journal.accountType(p.account)) {
                    AccountType.EXPENSE -> acc.spent += p.amount.toMixed()
                    AccountType.REVENUE -> acc.received += -p.amount.toMixed()
                    else -> Unit
                }
            }
        }
        return byPayee.map { (name, a) ->
            PayeeSummary(name, a.count, a.last, a.spent, a.received, declared = journal.isDeclaredPayee(name))
        }
    }

    /** payee directives for [payees], one per line, to paste at the top of a journal. Names with ; are quoted. */
    fun directives(payees: List<String>): String =
        payees.filter { it.isNotBlank() }.joinToString("\n") { p -> if (';' in p) "payee \"$p\"" else "payee $p" }

    /** The payee's transactions in [span], newest first. */
    fun transactions(journal: Journal, payee: String, span: DateSpan): List<Transaction> =
        journal.transactions.asReversed().filter { span.contains(it.date) && of(it.description) == payee }
}

/** How much a transaction moved: the sum of its positive real amounts, eg $4.50 for a coffee. */
fun transactionAmount(t: Transaction): MixedAmount =
    MixedAmount.sum(
        t.postings.filter { it.kind == PostingKind.REAL && it.amount.quantity.signum() > 0 }.map { it.amount.toMixed() }
    )
