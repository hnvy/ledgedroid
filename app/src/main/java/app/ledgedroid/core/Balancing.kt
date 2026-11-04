package app.ledgedroid.core

import java.math.BigDecimal

/**
 * Turn parsed transactions into balanced ones, in date order, like hledger's
 * journalBalanceTransactions (simplified):
 *
 *  1. balance assignments (a posting with only "= AMT") get the amount that makes the assertion true
 *  2. one missing amount per group (real, or [balanced virtual]) is inferred; if the rest of the
 *     group sums to several commodities, the posting is split into one per commodity
 *  3. each group must sum to zero at display precision, using costs (@, @@) where given;
 *     a two-commodity transaction with no costs is accepted as an implicit conversion
 *  4. running balances are updated and balance assertions checked
 *
 * Unbalanced transactions are errors; failed assertions are returned as warnings.
 */
internal fun balanceTransactions(
    raw: List<RawTransaction>,
    styles: Styles,
    sourceLines: List<String>,
    checkAssertions: Boolean = true,
): Pair<List<Transaction>, List<JournalIssue>> {
    val running = HashMap<String, HashMap<String, BigDecimal>>()
    val issues = ArrayList<JournalIssue>()
    val out = ArrayList<Transaction>(raw.size)

    fun excerpt(t: RawTransaction): String {
        val from = (t.line - 1).coerceIn(0, sourceLines.size)
        return sourceLines.subList(from, t.lastLine.coerceIn(from, sourceLines.size)).joinToString("\n")
    }

    fun balanceOf(account: String, inclusive: Boolean): Map<String, BigDecimal> =
        if (!inclusive) running[account] ?: emptyMap()
        else {
            val sum = HashMap<String, BigDecimal>()
            for ((a, m) in running) if (isSameOrSubaccount(a, account)) for ((c, q) in m) sum.merge(c, q, BigDecimal::add)
            sum
        }

    for (rt in raw.sortedWith(compareBy<RawTransaction>({ it.date }, { it.index }))) {
        val ps = rt.postings
        val amounts = arrayOfNulls<Amount>(ps.size)
        val inferred = BooleanArray(ps.size)
        for (k in ps.indices) { amounts[k] = ps[k].amount; inferred[k] = ps[k].inferred }

        // 1. balance assignments
        for (k in ps.indices) {
            val a = ps[k].assertion ?: continue
            if (amounts[k] != null) continue
            val c = a.amount.commodity
            var current = balanceOf(ps[k].account, a.inclusive)[c] ?: BigDecimal.ZERO
            for (j in 0 until k) {
                val other = amounts[j] ?: continue
                val matches = if (a.inclusive) isSameOrSubaccount(ps[j].account, ps[k].account) else ps[j].account == ps[k].account
                if (matches && other.commodity == c) current = current.add(other.quantity)
            }
            amounts[k] = Amount(c, a.amount.quantity.subtract(current))
            inferred[k] = true
        }

        // 2. infer missing amounts, per balancing group
        val extra = HashMap<Int, List<Amount>>() // posting index -> additional split amounts
        for (kind in listOf(PostingKind.REAL, PostingKind.BALANCED_VIRTUAL)) {
            val group = ps.indices.filter { ps[it].kind == kind }
            val missing = group.filter { amounts[it] == null }
            if (missing.size > 1) {
                val what = if (kind == PostingKind.REAL) "real" else "balanced virtual"
                throw JournalException(rt.line, "This transaction has ${missing.size} $what postings with no amount. Only one can be left blank.", excerpt(rt))
            }
            val m = missing.singleOrNull() ?: continue
            val sum = groupSum(group.mapNotNull { amounts[it] })
            val parts = sum.filter { it.value.signum() != 0 }.entries.toList()
            if (parts.isEmpty()) {
                amounts[m] = Amount("", BigDecimal.ZERO)
            } else {
                amounts[m] = Amount(parts[0].key, parts[0].value.negate())
                if (parts.size > 1) extra[m] = parts.drop(1).map { Amount(it.key, it.value.negate()) }
            }
            inferred[m] = true
        }
        for (k in ps.indices) if (amounts[k] == null) { amounts[k] = Amount("", BigDecimal.ZERO); inferred[k] = true }

        // 3. check each group balances
        for (kind in listOf(PostingKind.REAL, PostingKind.BALANCED_VIRTUAL)) {
            val groupAmounts = ps.indices.filter { ps[it].kind == kind }.flatMap { listOf(amounts[it]!!) + (extra[it] ?: emptyList()) }
            val sum = MixedAmount.of(groupSum(groupAmounts))
            if (sum.looksZero(styles)) continue
            val implicitConversion = kind == PostingKind.REAL &&
                groupAmounts.none { it.cost != null } &&
                sum.parts.size == 2 &&
                sum.parts.values.let { v -> v.first().signum() != v.last().signum() }
            if (implicitConversion) continue
            val what = if (kind == PostingKind.REAL) "real postings'" else "balanced virtual postings'"
            throw JournalException(
                rt.line,
                "This transaction is unbalanced. The $what sum should be 0 but is ${styles.format(sum)}.",
                excerpt(rt),
            )
        }

        // 4. build postings, update running balances, check assertions
        val postings = ArrayList<Posting>(ps.size)
        for (k in ps.indices) {
            val p = ps[k]
            val all = listOf(amounts[k]!!) + (extra[k] ?: emptyList())
            for ((n, amt) in all.withIndex()) {
                postings.add(
                    Posting(
                        account = p.account, amount = amt, kind = p.kind, status = p.status,
                        assertion = if (n == 0) p.assertion else null, comment = p.comment,
                        inferred = inferred[k] || n > 0, generated = p.generated, line = p.line,
                    )
                )
                running.getOrPut(p.account) { HashMap() }.merge(amt.commodity, amt.quantity, BigDecimal::add)
            }
            val a = p.assertion ?: continue
            if (!checkAssertions) continue
            val actual = balanceOf(p.account, a.inclusive)
            val c = a.amount.commodity
            val actualQ = actual[c] ?: BigDecimal.ZERO
            if (actualQ.compareTo(a.amount.quantity) != 0) {
                issues.add(
                    JournalIssue(
                        a.line,
                        "Balance assertion failed for ${p.account}: expected ${styles.format(c, a.amount.quantity)}, " +
                            "but the calculated balance is ${styles.format(c, actualQ)} " +
                            "(off by ${styles.format(c, actualQ.subtract(a.amount.quantity))}).",
                        isFailedAssertion = true,
                    )
                )
            }
            if (a.total) {
                val others = actual.filter { it.key != c && it.value.signum() != 0 }
                if (others.isNotEmpty()) {
                    issues.add(
                        JournalIssue(
                            a.line,
                            "Balance assertion (==) failed for ${p.account}: it should contain only " +
                                "${styles.format(c, a.amount.quantity)}, but also has ${styles.format(MixedAmount.of(others))}.",
                            isFailedAssertion = true,
                        )
                    )
                }
            }
        }

        out.add(
            Transaction(
                index = rt.index, date = rt.date, date2 = rt.date2, status = rt.status, code = rt.code,
                description = rt.description, comment = rt.comment, postings = postings, line = rt.line,
                lastLine = rt.lastLine,
            )
        )
    }
    return out to issues
}

private fun groupSum(amounts: List<Amount>): Map<String, BigDecimal> {
    val m = LinkedHashMap<String, BigDecimal>()
    for (a in amounts) {
        val (c, q) = a.balancingValue()
        m.merge(c, q, BigDecimal::add)
    }
    return m
}
