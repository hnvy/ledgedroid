package app.ledgedroid.core

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Colours for the branches of the account tree, one per top-level account, whatever the accounts are called.
 *
 * Each top-level account gets a hue (0 to 360) that the person can change. New ones are picked at random but
 * well apart from the hues already in use: each goes in the middle of the widest gap between them, give or
 * take a little, so colours never end up next to each other. Hues are in OKLCH, a colour space where equal
 * steps of hue look equally different and every hue has the same lightness, so no branch is much brighter or
 * harder to read than another; light and dark mode use a lightness that suits each.
 */
object BranchColors {

    /** The top-level account a row belongs to; accounts under <unbudgeted> by their own name. */
    fun topLevel(account: String): String {
        val name = account.removePrefix("$UNBUDGETED:")
        return if (name == UNBUDGETED) UNBUDGETED else name.substringBefore(':')
    }

    /** The top-level accounts of [journal], declared ones first, then in name order. */
    fun topLevels(journal: Journal): List<String> =
        (journal.declaredAccounts.map { topLevel(it) } + journal.accounts.map { topLevel(it) }).distinct()

    /** How far apart two hues are around the colour wheel, 0 to 180. */
    fun distance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * The part of the colour wheel random colours are picked from, [width] degrees from [start]. The full
     * wheel gives the starkest differences; a narrow arc gives colours that are related, eg all blues and
     * greens. See [rangeFor].
     */
    data class HueRange(val start: Float = 0f, val width: Float = 360f) {
        val isFull get() = width >= 360f

        /** How far into the range [hue] is, 0 to [width], or null if it's outside. */
        fun offsetOf(hue: Float): Float? {
            val t = (((hue - start) % 360f) + 360f) % 360f
            return if (isFull || t <= width + 0.01f) t else null
        }

        fun hueAt(offset: Float): Float = (((start + offset) % 360f) + 360f) % 360f
    }

    /**
     * The hue range for a contrast setting from 0 (subtle: neighbouring colours, within 60 degrees) to 1
     * (stark: the whole wheel), starting at [start].
     */
    fun rangeFor(contrast: Float, start: Float): HueRange =
        HueRange(start, 60f + 300f * contrast.coerceIn(0f, 1f))

    /**
     * A new hue well away from [taken], within [range]: in the widest gap between them, near its middle
     * (within a tenth of the gap either way). At the ends of a partial range, the gap runs to the end, and
     * the new hue goes near that end, which is as far from the others as it can be. With nothing taken,
     * anywhere in the range.
     */
    fun pickHue(taken: Collection<Float>, random: Random = Random.Default, range: HueRange = HueRange()): Float {
        val inside = taken.mapNotNull { range.offsetOf(it) }.sorted()
        if (inside.isEmpty()) return range.hueAt(random.nextFloat() * minOf(range.width, 360f))
        val jitter = (random.nextFloat() - 0.5f) * 0.2f
        if (range.isFull) {
            var gapStart = inside.last()
            var gap = inside.first() + 360f - inside.last()
            for (k in 1 until inside.size) {
                val g = inside[k] - inside[k - 1]
                if (g > gap) {
                    gap = g; gapStart = inside[k - 1]
                }
            }
            return range.hueAt(gapStart + gap * (0.5f + jitter))
        }
        // Each candidate spot, and how far it is from its nearest neighbour.
        val w = range.width
        var best = 0f
        var bestDistance = -1f
        val before = inside.first()
        if (before > bestDistance) {
            bestDistance = before; best = (before * (0.1f - jitter)).coerceAtLeast(0f)
        }
        val after = w - inside.last()
        if (after > bestDistance) {
            bestDistance = after; best = (w - after * (0.1f + jitter)).coerceAtMost(w)
        }
        for (k in 1 until inside.size) {
            val g = inside[k] - inside[k - 1]
            if (g / 2 > bestDistance) {
                bestDistance = g / 2; best = inside[k - 1] + g * (0.5f + jitter)
            }
        }
        return range.hueAt(best)
    }

    /** [existing] hues, plus new well-spaced ones for any of [names] without one. Existing hues are kept. */
    fun assign(
        existing: Map<String, Float>,
        names: List<String>,
        random: Random = Random.Default,
        range: HueRange = HueRange(),
    ): Map<String, Float> {
        if (names.all { it in existing }) return existing
        val out = LinkedHashMap(existing)
        for (n in names) if (n !in out) out[n] = pickHue(out.values, random, range)
        return out
    }

    /** New hues for all of [names], well spaced within [range], keeping hues for anything else. */
    fun shuffle(
        existing: Map<String, Float>,
        names: List<String>,
        random: Random = Random.Default,
        range: HueRange = HueRange(),
    ): Map<String, Float> = assign(existing - names.toSet(), names, random, range)

    /**
     * [hue] moved from [from] to [to], keeping its place relative to the range (a quarter of the way along
     * stays a quarter of the way along), so changing the contrast spreads or gathers the colours smoothly
     * without shuffling them. Hues outside [from] stay as they are.
     */
    fun remap(hue: Float, from: HueRange, to: HueRange): Float {
        val t = from.offsetOf(hue) ?: return hue
        return to.hueAt(t / from.width * to.width)
    }

    /**
     * The branch colour for [hue], as ARGB, for a light or dark background: the top-level account's colour at
     * [depth] 0, and a darker shade of it at each level below (down to a limit, so it stays readable).
     * [contrast] (0 to 1) also sets how vivid colours are: from soft at 0 to as vivid as the screen can show
     * at 1 (chroma is asked for beyond sRGB and brought back to the most each hue allows). [shading] (0 to 1)
     * sets how much darker each level gets: not at all at 0, up to about twice the default at 1. Shades
     * never get darker than a floor that keeps them visible on the background.
     */
    fun argb(hue: Float, dark: Boolean, depth: Int = 0, contrast: Float = 1f, shading: Float = DEFAULT_SHADING): Int {
        val steps = depth.coerceIn(0, 4)
        val c = contrast.coerceIn(0f, 1f).toDouble()
        // Softer than before at the subtle end, and much more vivid at the stark end.
        val vivid = 0.3 + 0.7 * c * c
        val step = 0.12 * shading.coerceIn(0f, 1f)
        return if (dark) oklch(maxOf(0.80 - step * steps, 0.42), 0.24 * vivid, hue.toDouble())
        else oklch(maxOf(0.60 - step * steps, 0.28), 0.26 * vivid, hue.toDouble())
    }

    /** How much darker sub-levels get by default (a step of about 0.07 lightness per level). */
    const val DEFAULT_SHADING = 0.6f

    /**
     * An OKLCH colour as opaque sRGB ARGB. If it's outside sRGB, chroma is reduced until it fits, which keeps
     * its lightness and hue.
     */
    fun oklch(lightness: Double, chroma: Double, hueDegrees: Double): Int {
        var c = chroma
        while (true) {
            val rgb = oklabToLinearSrgb(lightness, c, hueDegrees)
            if (rgb.all { it in -1e-4..1.0001 } || c <= 0.0) {
                val (r, g, b) = rgb.map { (gamma(it.coerceIn(0.0, 1.0)) * 255).roundToInt() }
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            c = (c - 0.005).coerceAtLeast(0.0)
        }
    }

    private fun oklabToLinearSrgb(l: Double, c: Double, hueDegrees: Double): List<Double> {
        val h = Math.toRadians(hueDegrees)
        val a = c * cos(h)
        val b = c * sin(h)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).let { it * it * it }
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).let { it * it * it }
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).let { it * it * it }
        return listOf(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
        )
    }

    private fun gamma(x: Double): Double = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055

    /** OKLab lightness of an ARGB colour, for tests. */
    internal fun lightnessOf(argb: Int): Double {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        val l = cbrt(0.4122214708 * r + 0.5363015264 * g + 0.0514459080 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    }
}
