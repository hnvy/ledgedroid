package app.ledgedroid.core

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The advanced filters in an account's list of transactions, on top of the report's filter. Everything
 * set has to match (unlike typed query terms of the same kind, which are ORed), so eg an amount between
 * two numbers can be asked for. Empty fields match everything.
 *
 *  - [text]: in the description (payee and note) or a comment on the transaction or the posting
 *  - [minAmount], [maxAmount]: the amount's size, whatever its sign (the In / Out buttons choose the
 *    sign), as typed; something that isn't a number is ignored (see [amountError])
 *  - [statuses]: unmarked, pending or cleared (a posting's own mark, else its transaction's)
 *  - [otherAccount]: another account in the transaction contains this, eg "food" or "expenses:fuel"
 *  - [tag]: "trip", or "trip=lisbon" for a value too; on the transaction or the posting
 *  - [commodities]: only amounts in these
 *  - [from], [to]: dates, both included
 *
 * Text matches ignore case.
 */
data class RegisterFilter(
    val text: String = "",
    val minAmount: String = "",
    val maxAmount: String = "",
    val statuses: Set<Status> = emptySet(),
    val otherAccount: String = "",
    val tag: String = "",
    val commodities: Set<String> = emptySet(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    private val min: BigDecimal? = number(minAmount)
    private val max: BigDecimal? = number(maxAmount)
    private val tagName = tag.substringBefore('=').trim().lowercase()
    private val tagValue = if ('=' in tag) tag.substringAfter('=').trim().lowercase() else null

    /** How many of the filters are set, for the "Advanced filters (3)" label. */
    val activeCount: Int
        get() = listOf(
            text.isNotBlank(), min != null || max != null, statuses.isNotEmpty(), otherAccount.isNotBlank(),
            tagName.isNotEmpty(), commodities.isNotEmpty(), from != null || to != null,
        ).count { it }

    val isActive: Boolean get() = activeCount > 0

    /** What's wrong with the amounts typed, or null. */
    val amountError: String?
        get() = when {
            minAmount.isNotBlank() && min == null -> "\"${minAmount.trim()}\" isn't a number"
            maxAmount.isNotBlank() && max == null -> "\"${maxAmount.trim()}\" isn't a number"
            min != null && max != null && min > max -> "The smallest amount is more than the largest"
            else -> null
        }

    /** What's wrong with the dates, or null. */
    val dateError: String?
        get() = if (from != null && to != null && to.isBefore(from)) "The end date is before the start date" else null

    fun matches(t: Transaction, p: Posting): Boolean {
        if (!isActive) return true
        if (from != null && t.date.isBefore(from)) return false
        if (to != null && t.date.isAfter(to)) return false
        if (commodities.isNotEmpty() && p.amount.commodity !in commodities) return false
        if (statuses.isNotEmpty() && (if (p.status != Status.UNMARKED) p.status else t.status) !in statuses) return false
        val size = p.amount.quantity.abs()
        if (min != null && size < min) return false
        if (max != null && size > max) return false
        val needle = text.trim()
        if (needle.isNotEmpty() && listOf(t.description, t.comment, p.comment).none { it.contains(needle, ignoreCase = true) }) {
            return false
        }
        val other = otherAccount.trim()
        if (other.isNotEmpty() && t.postings.none { it !== p && it.account != p.account && it.account.contains(other, ignoreCase = true) }) {
            return false
        }
        if (tagName.isNotEmpty() && (tagsIn(t.comment) + tagsIn(p.comment)).none { (name, value) ->
                name.lowercase().contains(tagName) && (tagValue == null || value.lowercase().contains(tagValue))
            }
        ) return false
        return true
    }

    /** A short description of what's set, eg "“cafe”, 50 to 100, cleared, with food". */
    fun describe(): String {
        val parts = ArrayList<String>()
        if (text.isNotBlank()) parts.add("\u201C${text.trim()}\u201D")
        when {
            min != null && max != null -> parts.add("${min.toPlainString()} to ${max.toPlainString()}")
            min != null -> parts.add("at least ${min.toPlainString()}")
            max != null -> parts.add("at most ${max.toPlainString()}")
        }
        if (statuses.isNotEmpty()) {
            parts.add(Status.entries.filter { it in statuses }.joinToString(" or ") {
                when (it) {
                    Status.UNMARKED -> "unmarked"
                    Status.PENDING -> "pending"
                    Status.CLEARED -> "cleared"
                }
            })
        }
        if (otherAccount.isNotBlank()) parts.add("with ${otherAccount.trim()}")
        if (tagName.isNotEmpty()) parts.add("tag ${tag.trim()}")
        if (commodities.isNotEmpty()) parts.add(commodities.sorted().joinToString(" or ") { it.ifEmpty { "no currency" } })
        when {
            from != null && to != null -> parts.add("$from to $to")
            from != null -> parts.add("from $from")
            to != null -> parts.add("until $to")
        }
        return parts.joinToString(", ")
    }

    companion object {
        val NONE = RegisterFilter()

        /** "12.50", "12,50" or "1,234.50" (as typed in the form's amounts); null if not a number. */
        internal fun number(s: String): BigDecimal? {
            val t = s.trim().removePrefix("+").removePrefix("-").trim()
            if (t.isEmpty()) return null
            val normal = when {
                t.contains('.') && t.contains(',') -> t.replace(",", "")
                t.contains(',') -> t.replace(',', '.')
                else -> t
            }
            return normal.toBigDecimalOrNull()?.abs()
        }
    }
}
