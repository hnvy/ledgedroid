package app.ledgedroid.core

import java.math.BigDecimal

/**
 * Which postings an account's register lists by the sign of their amount in the journal: the same as
 * amt:>0 (POSITIVE) and amt:<0 (NEGATIVE) in the filter. Zero amounts are in neither.
 */
enum class AmountSign(val query: String) {
    ALL(""),
    POSITIVE("amt:>0"),
    NEGATIVE("amt:<0");

    fun matches(quantity: BigDecimal): Boolean = when (this) {
        ALL -> true
        POSITIVE -> quantity.signum() > 0
        NEGATIVE -> quantity.signum() < 0
    }
}

/**
 * One side of the register's sign filter, named for what it means for the account: for a credit card
 * (a liability) negative amounts are charges and positive ones payments.
 *
 * [label] is the short button text, [total] labels the total, [summary] says what the list shows
 * ("charges"), and [empty] is shown when there are none in the period.
 */
data class SignChoice(
    val sign: AmountSign,
    val label: String,
    val total: String,
    val summary: String,
    val empty: String,
)

object SignChoices {

    /**
     * The two sides for an account of [type], the account's usual direction first: In and Out for
     * assets, Charges and Payments for liabilities, Spending and Refunds for expenses, Received and
     * Reversed for revenues.
     *
     * Other accounts (equity, or no known type) get Positive and Negative, meaning the sign as shown in
     * the register: [displaySign] is -1 when the register flips signs (a liability or revenue opened
     * from a balance sheet or income statement, or --invert), so Positive then means amt:<0.
     */
    fun forAccount(type: AccountType?, displaySign: Int): List<SignChoice> = when (type) {
        AccountType.ASSET, AccountType.CASH -> listOf(
            SignChoice(AmountSign.POSITIVE, "In", "Total in", "money in", "Nothing came in during this period."),
            SignChoice(AmountSign.NEGATIVE, "Out", "Total out", "money out", "Nothing went out during this period."),
        )

        AccountType.LIABILITY -> listOf(
            SignChoice(AmountSign.NEGATIVE, "Charges", "Total charges", "charges", "No charges in this period."),
            SignChoice(AmountSign.POSITIVE, "Payments", "Total payments", "payments", "No payments in this period."),
        )

        AccountType.EXPENSE -> listOf(
            SignChoice(AmountSign.POSITIVE, "Spending", "Total spent", "spending", "No spending in this period."),
            SignChoice(AmountSign.NEGATIVE, "Refunds", "Total refunded", "refunds", "No refunds in this period."),
        )

        AccountType.REVENUE -> listOf(
            SignChoice(AmountSign.NEGATIVE, "Received", "Total received", "income received", "Nothing received in this period."),
            SignChoice(AmountSign.POSITIVE, "Reversed", "Total reversed", "reversals", "No reversals in this period."),
        )

        AccountType.EQUITY, null -> {
            val shownPositive = if (displaySign < 0) AmountSign.NEGATIVE else AmountSign.POSITIVE
            val shownNegative = if (displaySign < 0) AmountSign.POSITIVE else AmountSign.NEGATIVE
            listOf(
                SignChoice(shownPositive, "Positive", "Total positive", "positive amounts", "No positive amounts in this period."),
                SignChoice(shownNegative, "Negative", "Total negative", "negative amounts", "No negative amounts in this period."),
            )
        }
    }
}
