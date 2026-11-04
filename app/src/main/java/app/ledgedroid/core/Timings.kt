package app.ledgedroid.core

/** How long each step of an operation took, eg saving a transaction, to find what's slow on a device. */
class Timings(val what: String) {
    private val start = System.nanoTime()
    private var last = start
    private val steps = ArrayList<Pair<String, Long>>()

    /** The time since the previous step (or the start) was spent on [step]. */
    fun step(step: String) {
        val now = System.nanoTime()
        steps.add(step to (now - last) / 1_000_000)
        last = now
    }

    val totalMs: Long get() = (last - start) / 1_000_000

    /** Eg "Adding: read 40 ms, check 55 ms, write 30 ms (total 125 ms)". */
    override fun toString(): String =
        "$what: " + steps.joinToString(", ") { (s, ms) -> "$s $ms ms" } + " (total $totalMs ms)"
}
