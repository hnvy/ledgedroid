package app.ledgedroid.core

import java.time.LocalDate

enum class Status { UNMARKED, PENDING, CLEARED }

/** Real postings must balance; (virtual) ones needn't; [balanced virtual] ones must balance among themselves. */
enum class PostingKind { REAL, VIRTUAL, BALANCED_VIRTUAL }

/** The five main account types, plus Cash (a kind of Asset), as in hledger. */
enum class AccountType {
    ASSET, CASH, LIABILITY, EQUITY, REVENUE, EXPENSE;

    val isAsset get() = this == ASSET || this == CASH

    companion object {
        /** Parse a type: tag value, accepting hledger's one-letter codes and long names. */
        fun parse(s: String): AccountType? = when (s.trim().lowercase()) {
            "a", "asset", "assets" -> ASSET
            "c", "cash" -> CASH
            "l", "liability", "liabilities" -> LIABILITY
            "e", "equity", "v", "conversion" -> EQUITY
            "r", "revenue", "revenues", "income", "g", "gain", "gains" -> REVENUE
            "x", "expense", "expenses" -> EXPENSE
            else -> null
        }

        /** Guess an account's type from its top-level name, like hledger's accountNameInferType. */
        fun infer(account: String): AccountType? = when (account.substringBefore(':').lowercase()) {
            "asset", "assets" -> if (CASH_REGEX.containsMatchIn(account)) CASH else ASSET
            "debt", "debts", "liability", "liabilities" -> LIABILITY
            "equity" -> EQUITY
            "income", "incomes", "revenue", "revenues" -> REVENUE
            "expense", "expenses" -> EXPENSE
            else -> null
        }

        /** The names shown for each type, eg in Settings. */
        fun label(type: AccountType): String = when (type) {
            ASSET -> "Asset"
            CASH -> "Cash"
            LIABILITY -> "Liability"
            EQUITY -> "Equity"
            REVENUE -> "Revenue"
            EXPENSE -> "Expense"
        }

        private val CASH_REGEX = Regex(
            "^assets?(:.+)?:(cash|bank|che(ck|que)ing|savings?|current)(:|$)", RegexOption.IGNORE_CASE
        )
    }
}

/** A balance assertion: = (one commodity), == (sole commodity), with * meaning "including subaccounts". */
data class BalanceAssertion(
    val amount: Amount,
    val total: Boolean,
    val inclusive: Boolean,
    val line: Int,
)

data class Posting(
    val account: String,
    val amount: Amount,
    val kind: PostingKind = PostingKind.REAL,
    val status: Status = Status.UNMARKED,
    val assertion: BalanceAssertion? = null,
    val comment: String = "",
    /** True if Ledgedroid filled in this amount (missing amount or balance assignment). */
    val inferred: Boolean = false,
    /** True if an auto posting rule (=) added this posting. */
    val generated: Boolean = false,
    val line: Int = 0,
)

data class Transaction(
    /** Position in the file (0-based), used as a tie-breaker when sorting by date. */
    val index: Int,
    val date: LocalDate,
    val date2: LocalDate? = null,
    val status: Status = Status.UNMARKED,
    val code: String = "",
    val description: String = "",
    val comment: String = "",
    val postings: List<Posting>,
    val line: Int = 0,
    val lastLine: Int = line,
    /** Generated from a periodic rule by --forecast; not in the journal file. line is the rule's. */
    val forecast: Boolean = false,
    /** The included file it's in (its lines are that file's), or null for the main journal file. */
    val file: String? = null,
)

/** A posting in a periodic transaction rule (~) or auto posting rule (=). */
data class RulePosting(
    val account: String,
    val kind: PostingKind,
    val status: Status,
    /** Null when left blank (only allowed in periodic rules, where it is inferred). */
    val amount: Amount?,
    /** An auto posting amount written as *N: multiply the matched posting's amount by N. */
    val isMultiplier: Boolean = false,
    val line: Int,
)

/** A periodic transaction rule: "~ monthly  description" followed by postings. Used as budget goals. */
data class PeriodicRule(
    val periodText: String,
    val interval: Interval,
    val span: DateSpan,
    val status: Status,
    val description: String,
    val postings: List<RulePosting>,
    val line: Int,
    val lastLine: Int,
    /** The included file it's in, or null for the main journal file. */
    val file: String? = null,
) {
    /** The dates this rule generates a transaction on, within [requested] (hledger's periodicTransactionDates). */
    fun dates(requested: DateSpan): List<LocalDate> {
        val start = span.start ?: requested.start ?: return emptyList()
        val end = span.end ?: requested.end ?: return emptyList()
        return splitSpan(interval, start, end, adjust = span.start == null).map { it.start!! }
            .filter { requested.contains(it) }
    }
}

/** An auto posting rule: "= QUERY" followed by postings to add after each matching posting. */
data class AutoRule(
    val queryText: String,
    val query: Query,
    val postings: List<RulePosting>,
    val line: Int,
    /** The included file it's in, or null for the main journal file. */
    val file: String? = null,
)

/** Where an account's type comes from (see [Journal.typeInfo]). */
enum class TypeSource {
    /** Set in the app's settings, for this account or [TypeInfo.from], an account above it. */
    SET_IN_APP,

    /** A type: tag on an account directive, for this account or the account above it named in from. */
    DECLARED,

    /** Guessed from the top-level name (assets, liabilities, equity, income/revenue, expenses), like hledger. */
    FROM_NAME,

    /** None: the account isn't in the balance sheet or income statement. */
    NONE,
}

/** An account's type, where it comes from, and the account it was set on (the account itself or one above). */
data class TypeInfo(val type: AccountType?, val source: TypeSource, val from: String? = null)

/** A problem found while reading a journal. Errors stop the report; warnings don't. */
data class JournalIssue(
    val line: Int,
    val message: String,
    val isFailedAssertion: Boolean = false,
    /** The included file it's in, or null for the main journal file. */
    val file: String? = null,
)

class JournalException(val line: Int, message: String, val excerpt: String = "") : Exception(message) {
    override fun toString(): String = if (line > 0) "line $line: $message" else message.orEmpty()
}

data class Journal(
    /** Transactions sorted by date, then file order. */
    val transactions: List<Transaction>,
    val styles: Styles,
    /** Accounts declared with account directives, in declaration order. */
    val declaredAccounts: List<String> = emptyList(),
    /** Account types set by type: tags on account directives. */
    val declaredTypes: Map<String, AccountType> = emptyMap(),
    /** Market prices from P directives, in file order. */
    val prices: List<MarketPrice> = emptyList(),
    /** The files read through include directives, by name, in the order they were included. */
    val includedFiles: List<String> = emptyList(),
    /** Payees declared with payee directives, in declaration order, like hledger's jdeclaredpayees. */
    val declaredPayees: List<String> = emptyList(),
    val warnings: List<JournalIssue> = emptyList(),
    val periodicRules: List<PeriodicRule> = emptyList(),
    val autoRules: List<AutoRule> = emptyList(),
    /** Whether auto posting rules were applied (--auto). */
    val autoApplied: Boolean = false,
    /**
     * Types set in the app's settings, eg expns as an expense account. They apply to subaccounts too, and
     * win over a type: tag on the same account, since they're set on purpose in the app.
     */
    val assignedTypes: Map<String, AccountType> = emptyMap(),
) {
    private val declarationOrder: Map<String, Int> = declaredAccounts.withIndex().associate { it.value to it.index }
    private val typeCache = HashMap<String, AccountType?>()

    /**
     * The account's type: set in the app or declared, on it or its nearest ancestor that has one (the
     * app's setting first on the same account), else guessed from its name.
     */
    fun accountType(account: String): AccountType? = typeCache.getOrPut(account) { typeInfo(account).type }

    /** The account's type and where it comes from, for showing in Settings. */
    fun typeInfo(account: String): TypeInfo {
        var a: String? = account
        while (a != null) {
            assignedTypes[a]?.let { return TypeInfo(it, TypeSource.SET_IN_APP, a) }
            declaredTypes[a]?.let { return TypeInfo(it, TypeSource.DECLARED, a) }
            a = parentAccount(a)
        }
        return AccountType.infer(account)?.let { TypeInfo(it, TypeSource.FROM_NAME) } ?: TypeInfo(null, TypeSource.NONE)
    }

    /**
     * What the account's type would be without a type set in the app on this account itself: from an
     * account above it, a type: tag or its name. What "Automatic" means in Settings.
     */
    fun automaticTypeInfo(account: String): TypeInfo = copy(assignedTypes = assignedTypes - account).typeInfo(account)

    fun declarationIndex(account: String): Int? = declarationOrder[account]

    private val declaredPayeeSet: Set<String> by lazy { declaredPayees.toHashSet() }

    /** Whether [payee] is declared with a payee directive. Exact match, as in hledger check payees. */
    fun isDeclaredPayee(payee: String): Boolean = payee in declaredPayeeSet

    val accounts: List<String> by lazy {
        transactions.flatMap { t -> t.postings.map { it.account } }.distinct().sorted()
    }

    val firstDate: LocalDate? get() = transactions.firstOrNull()?.date
    val lastDate: LocalDate? get() = transactions.lastOrNull()?.date
}

fun parentAccount(account: String): String? =
    account.lastIndexOf(':').let { if (it < 0) null else account.substring(0, it) }

fun accountLevel(account: String): Int = account.count { it == ':' } + 1

/** Clip an account name to at most [depth] components (hledger's clipAccountName). */
fun clipAccount(account: String, depth: Int?): String {
    if (depth == null) return account
    var idx = -1
    repeat(depth) {
        idx = account.indexOf(':', idx + 1)
        if (idx < 0) return account
    }
    return account.substring(0, idx)
}

/**
 * Leave out the first [n] parts of an account name, like hledger's --drop (accountNameDrop): "..." when
 * nothing is left, and <unbudgeted> kept in front of the budget report's unbudgeted accounts.
 */
fun dropAccountName(n: Int, account: String): String {
    if (n <= 0) return account
    if (account == UNBUDGETED) return account
    val prefix = "$UNBUDGETED:"
    if (account.startsWith(prefix)) {
        val rest = dropAccountName(n, account.removePrefix(prefix))
        return if (rest == "...") UNBUDGETED else prefix + rest
    }
    val parts = account.split(':').drop(n)
    return if (parts.isEmpty()) "..." else parts.joinToString(":")
}

fun isSameOrSubaccount(account: String, parent: String): Boolean =
    account == parent || account.startsWith("$parent:")

/**
 * The tags in a comment, as name to value, like hledger: a word followed by a colon, with the value
 * running to the next comma or the end of the line. "; trip:lisbon, reviewed:" has two tags.
 */
fun tagsIn(comment: String): List<Pair<String, String>> =
    TAG_REGEX.findAll(comment).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

private val TAG_REGEX = Regex("(?:^|[\\s,])([^\\s,:]+):([^,\\n]*)")
