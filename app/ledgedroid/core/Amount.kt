package app.ledgedroid.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.TreeMap

/** Which side of the number the commodity symbol is written on. */
enum class Side { LEFT, RIGHT }

/**
 * How amounts of one commodity are displayed (hledger's AmountStyle, simplified).
 * groupSizes lists digit group sizes starting next to the decimal mark; the last one repeats
 * (so [3] is 1,000,000 and [3, 2] is 9,99,99,999).
 */
data class AmountStyle(
    val side: Side = Side.RIGHT,
    val spaced: Boolean = true,
    val decimalMark: Char = '.',
    val groupMark: Char? = null,
    val groupSizes: List<Int> = emptyList(),
    val precision: Int = 0,
)

/** A cost written with @ (unit cost) or @@ (total cost). */
data class Cost(val isTotal: Boolean, val commodity: String, val quantity: BigDecimal)

/** A single-commodity amount, possibly with a cost. */
data class Amount(val commodity: String, val quantity: BigDecimal, val cost: Cost? = null) {

    /** The commodity and quantity this amount contributes when checking that a transaction balances. */
    fun balancingValue(): Pair<String, BigDecimal> = when {
        cost == null -> commodity to quantity
        cost.isTotal -> cost.commodity to cost.quantity.abs().multiply(BigDecimal(quantity.signum()))
        else -> cost.commodity to cost.quantity.multiply(quantity)
    }

    fun toMixed(): MixedAmount = MixedAmount.of(commodity, quantity)
}

/**
 * A sum of amounts in zero or more commodities, with costs stripped (like hledger's
 * MixedAmount after mixedAmountStripCosts). Zero parts are dropped and quantities are
 * normalised so that equality is numeric.
 */
class MixedAmount private constructor(val parts: Map<String, BigDecimal>) {

    operator fun plus(other: MixedAmount): MixedAmount {
        if (other.parts.isEmpty()) return this
        if (parts.isEmpty()) return other
        val m = TreeMap(parts)
        for ((c, q) in other.parts) m[c] = (m[c] ?: BigDecimal.ZERO).add(q)
        return of(m)
    }

    operator fun unaryMinus(): MixedAmount = MixedAmount(parts.mapValuesTo(TreeMap()) { it.value.negate() })

    operator fun times(sign: Int): MixedAmount = if (sign >= 0) this else -this

    fun quantityOf(commodity: String): BigDecimal = parts[commodity] ?: BigDecimal.ZERO

    val isZero: Boolean get() = parts.isEmpty()

    /** True if every part would display as zero at its commodity's display precision. */
    fun looksZero(styles: Styles): Boolean = parts.all { (c, q) ->
        q.setScale(styles.styleFor(c).precision, RoundingMode.HALF_EVEN).signum() == 0
    }

    /** A single number used for sorting by amount (hledger compares mixed amounts structurally; this is close enough). */
    fun sortKey(): BigDecimal = parts.values.fold(BigDecimal.ZERO, BigDecimal::add)

    override fun equals(other: Any?) = other is MixedAmount && other.parts == parts
    override fun hashCode() = parts.hashCode()
    override fun toString() =
        if (parts.isEmpty()) "0" else parts.entries.joinToString(", ") { (c, q) -> "${q.toPlainString()} $c".trim() }

    companion object {
        val ZERO = MixedAmount(emptyMap())

        fun of(commodity: String, quantity: BigDecimal): MixedAmount =
            if (quantity.signum() == 0) ZERO
            else MixedAmount(TreeMap(mapOf(commodity to quantity.stripTrailingZeros())))

        fun of(map: Map<String, BigDecimal>): MixedAmount {
            val m = TreeMap<String, BigDecimal>()
            for ((c, q) in map) if (q.signum() != 0) m[c] = q.stripTrailingZeros()
            return if (m.isEmpty()) ZERO else MixedAmount(m)
        }

        fun sum(items: Iterable<MixedAmount>): MixedAmount = items.fold(ZERO, MixedAmount::plus)
    }
}

/** The display styles for all commodities in a journal, and amount formatting. */
class Styles(private val byCommodity: Map<String, AmountStyle>) {

    fun styleFor(commodity: String): AmountStyle = byCommodity[commodity] ?: DEFAULT

    fun commodities(): Set<String> = byCommodity.keys

    /** The same styles without digit group marks (for plain-number output like CSV). */
    fun withoutDigitGroups(): Styles = Styles(byCommodity.mapValues { it.value.copy(groupMark = null, groupSizes = emptyList()) })

    /** Format one commodity's quantity, e.g. "$-1,234.50" or "-3 AAPL". */
    fun format(commodity: String, quantity: BigDecimal): String {
        val st = styleFor(commodity)
        val rounded = quantity.setScale(st.precision, RoundingMode.HALF_EVEN)
        val negative = rounded.signum() < 0
        val number = formatNumber(rounded.abs(), st)
        val signed = if (negative) "-$number" else number
        if (commodity.isEmpty()) return signed
        val sym = quoteIfNeeded(commodity)
        val sp = if (st.spaced) " " else ""
        return if (st.side == Side.LEFT) "$sym$sp$signed" else "$signed$sp$sym"
    }

    /** One display line per non-zero commodity, or "0" if the amount looks zero. */
    fun formatLines(amount: MixedAmount): List<String> {
        val lines = amount.parts
            .filter { (c, q) -> q.setScale(styleFor(c).precision, RoundingMode.HALF_EVEN).signum() != 0 }
            .map { (c, q) -> format(c, q) }
        return lines.ifEmpty { listOf("0") }
    }

    fun format(amount: MixedAmount): String = formatLines(amount).joinToString(", ")

    /** True if the displayed amount is negative (for colouring). Mixed signs count as not negative. */
    fun looksNegative(amount: MixedAmount): Boolean {
        val shown = amount.parts.filter { (c, q) ->
            q.setScale(styleFor(c).precision, RoundingMode.HALF_EVEN).signum() != 0
        }
        return shown.isNotEmpty() && shown.values.all { it.signum() < 0 }
    }

    private fun formatNumber(abs: BigDecimal, st: AmountStyle): String {
        val plain = abs.toPlainString()
        val intPart = plain.substringBefore('.')
        val fracPart = if (plain.contains('.')) plain.substringAfter('.') else ""
        val grouped = if (st.groupMark != null && st.groupSizes.isNotEmpty()) group(intPart, st.groupMark, st.groupSizes) else intPart
        return if (fracPart.isEmpty()) grouped else grouped + st.decimalMark + fracPart
    }

    private fun group(digits: String, mark: Char, sizes: List<Int>): String {
        val out = ArrayList<String>()
        var end = digits.length
        var i = 0
        while (end > 0) {
            val size = sizes[minOf(i, sizes.lastIndex)].coerceAtLeast(1)
            val start = maxOf(0, end - size)
            out.add(digits.substring(start, end))
            end = start
            i++
        }
        return out.asReversed().joinToString(mark.toString())
    }

    companion object {
        val DEFAULT = AmountStyle()
        val EMPTY = Styles(emptyMap())

        fun quoteIfNeeded(symbol: String): String =
            if (symbol.any { isNonSimpleCommodityChar(it) }) "\"$symbol\"" else symbol
    }
}

/** Characters that can't appear in an unquoted commodity symbol (from hledger's isNonsimpleCommodityChar). */
fun isNonSimpleCommodityChar(c: Char): Boolean =
    c.isDigit() || c.isWhitespace() || c in "-+.@*;\"{}=,()[]"
