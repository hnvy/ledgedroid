package app.ledgedroid.core

/** A piece of the About text: a heading, a paragraph, or a bullet point. */
sealed interface AboutBlock {
    data class Heading(val text: String, val level: Int) : AboutBlock
    data class Paragraph(val text: String) : AboutBlock
    data class Bullet(val text: String) : AboutBlock
}

/**
 * The About text (Settings › About), which the release workflow can set: its own input, or the repository
 * variable LEDGEDROID_ABOUT, else the file app/src/main/assets/about.md. It's read with light formatting:
 * # and ## headings, - or * bullets, blank lines between paragraphs, and links (http… and https…).
 * {version}, {versionCode}, {commit}, {built} and {repository} are filled in from the build; a paragraph
 * whose details aren't known (a build not made by the workflow) is left out rather than shown half-filled.
 */
object About {
    val PLACEHOLDERS = listOf("version", "versionCode", "commit", "built", "repository")
    private val URL = Regex("https?://[^\\s<>\"]+")

    /** The blocks of [text], with [values] filled in. */
    fun blocks(text: String, values: Map<String, String>): List<AboutBlock> {
        val out = ArrayList<AboutBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out.add(AboutBlock.Paragraph(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> {
                    flush()
                    val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
                    out.add(AboutBlock.Heading(line.drop(level).trim(), level))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    out.add(AboutBlock.Bullet(line.drop(2).trim()))
                }
                else -> paragraph.add(line)
            }
        }
        flush()
        return out.mapNotNull { b -> fill(b, values) }
    }

    /** [b] with the placeholders filled in, or null if one of them isn't known. */
    private fun fill(b: AboutBlock, values: Map<String, String>): AboutBlock? {
        fun f(s: String): String? {
            var t = s
            for (p in PLACEHOLDERS) {
                if ("{$p}" !in t) continue
                val v = values[p]?.takeIf { it.isNotBlank() } ?: return null
                t = t.replace("{$p}", v)
            }
            return t
        }
        return when (b) {
            is AboutBlock.Heading -> f(b.text)?.let { b.copy(text = it) }
            is AboutBlock.Paragraph -> f(b.text)?.let { b.copy(text = it) }
            is AboutBlock.Bullet -> f(b.text)?.let { b.copy(text = it) }
        }
    }

    /** Where the links are in [text], as ranges; full stops and brackets after a link aren't part of it. */
    fun links(text: String): List<IntRange> = URL.findAll(text).map { m ->
        var end = m.range.last
        while (end > m.range.first && text[end] in ".,;:!?)]'") end--
        m.range.first..end
    }.toList()

    /** The build details the workflow writes (key=value lines), eg version=1.2.0. */
    fun properties(text: String): Map<String, String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}
