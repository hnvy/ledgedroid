package app.ledgedroid.core

/** Autocompletion for the add-transaction form, from what the journal already contains. */
class EntrySuggestions private constructor(
    private val accountUses: Map<String, Int>,
    private val commodityUses: Map<String, Int>,
    /** Payees (the part of the description before |), most recently used first. */
    val payees: List<String>,
    /** Payees declared with payee directives, in declaration order. */
    val declaredPayees: List<String> = emptyList(),
) {
    constructor(journal: Journal) : this(
        accountUses = HashMap<String, Int>().also { uses ->
            for (t in journal.transactions) for (p in t.postings) if (!p.generated) uses.merge(p.account, 1, Int::plus)
            for (a in journal.declaredAccounts) uses.putIfAbsent(a, 0)
        },
        commodityUses = HashMap<String, Int>().also { uses ->
            for (t in journal.transactions) for (p in t.postings) {
                if (!p.generated && !p.inferred) uses.merge(p.amount.commodity, 1, Int::plus)
            }
        },
        payees = LinkedHashSet<String>().also { recent ->
            for (t in journal.transactions.asReversed()) {
                val payee = Payees.of(t.description)
                if (payee.isNotEmpty()) recent.add(payee)
            }
        }.toList(),
        declaredPayees = journal.declaredPayees,
    )

    private val declaredPayeeSet: Set<String> = declaredPayees.toHashSet()

    /** Whether the journal declares any payees. */
    val hasDeclaredPayees: Boolean get() = declaredPayees.isNotEmpty()

    fun isDeclaredPayee(payee: String): Boolean = payee.trim() in declaredPayeeSet

    /** Declared payees, the most recently used first, then those never used in declaration order. */
    private val declaredByRecency: List<String> by lazy {
        val used = payees.filter { it in declaredPayeeSet }
        val usedSet = used.toHashSet()
        used + declaredPayees.filter { it !in usedSet }
    }

    /** Accounts, most used first. Declared but unused accounts come last. */
    val accounts: List<String> =
        accountUses.keys.sortedWith(compareByDescending<String> { accountUses.getValue(it) }.thenBy { it })

    /** Commodity symbols, most used first. */
    val commodities: List<String> =
        commodityUses.keys.sortedWith(compareByDescending<String> { commodityUses.getValue(it) }.thenBy { it })

    /** The commodity to suggest for a new amount. */
    val defaultCommodity: String = commodities.firstOrNull() ?: ""

    /**
     * These suggestions plus a transaction just added, without reading the journal again, so the next
     * transaction in a row can use its payee and accounts straight away.
     */
    fun including(draft: TransactionDraft): EntrySuggestions {
        val accounts = HashMap(accountUses)
        val commodities = HashMap(commodityUses)
        for (i in draft.used) {
            val p = draft.postings[i]
            accounts.merge(p.account.trim(), 1, Int::plus)
            if (p.amount.isNotBlank()) commodities.merge(p.commodity.trim(), 1, Int::plus)
        }
        val payee = draft.payee.trim()
        val payees = if (payee.isEmpty()) payees else listOf(payee) + payees.filter { it != payee }
        return EntrySuggestions(accounts, commodities, payees, declaredPayees)
    }

    fun isKnownAccount(account: String): Boolean = account.trim() in accountUses

    /**
     * Accounts containing every word typed (words split at spaces or colons, case-insensitive), so
     * "ex din" finds expenses:food:dining. Accounts with a part starting with the last word come first,
     * then the most used. With nothing typed, the most used accounts.
     */
    fun accountsMatching(text: String, limit: Int = 8, intelligent: Boolean = false, fuzzy: Boolean = true): List<String> {
        // Intelligent mode: iadd's matching (words anywhere, or with [fuzzy] the starts of the account's
        // parts), most used first. Stops at [limit] matches rather than checking every account.
        if (intelligent) return accounts.asSequence().filter { a -> a != text.trim() && Intelligent.matches(text, a, fuzzy) }.take(limit).toList()
        val words = text.lowercase().split(Regex("[\\s:]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return accounts.take(limit)
        val last = words.last()
        return accounts
            .filter { a -> a != text.trim() && a.lowercase().let { l -> words.all { l.contains(it) } } }
            .sortedBy { a -> if (a.lowercase().split(':').any { it.startsWith(last) }) 0 else 1 }
            .take(limit)
    }

    /**
     * Payees containing the text, those starting with it first, then the most recent. With
     * [declaredOnly], only payees declared with a payee directive (including declared ones not used yet).
     */
    fun payeesMatching(text: String, limit: Int = 6, declaredOnly: Boolean = false, intelligent: Boolean = false): List<String> {
        val t = text.trim().lowercase()
        val from = if (declaredOnly) declaredByRecency else payees
        if (t.isEmpty()) return from.take(limit)
        return from
            // Intelligent mode: every word typed, anywhere in the payee ("cafe corner" finds Corner Cafe).
            .filter { (if (intelligent) Intelligent.matches(t, it) else it.lowercase().contains(t)) && it != text.trim() }
            .sortedBy { if (it.lowercase().startsWith(t)) 0 else 1 }
            .take(limit)
    }

    /** Whether [text] is exactly a payee the journal has (or declares). */
    fun isKnownPayee(text: String): Boolean = text.trim().let { it in declaredPayeeSet || it in payeeSet }

    private val payeeSet: Set<String> by lazy { payees.toHashSet() }

    fun isKnownCommodity(text: String): Boolean = text.trim() in commodityUses

    fun commoditiesMatching(text: String, limit: Int = 6): List<String> {
        val t = text.trim().lowercase()
        return commodities.filter { it.isNotEmpty() && it.lowercase().contains(t) && it != text.trim() }.take(limit)
    }
}
