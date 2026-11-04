package app.ledgedroid.core

/** A file found for an include directive: its [name] (its path from the main file's folder) and [text]. */
data class IncludedFile(val name: String, val text: String)

/**
 * Finds the files an include directive names. [pattern] is as written (a path, maybe with * or ?
 * wildcards in its last part); [from] is the including file's name, null for the main file, so relative
 * paths can be followed from its folder. Returns the matching files (none if none match), or throws
 * [IncludeException] when they can't be read at all (eg the folder hasn't been made available).
 */
fun interface IncludeResolver {
    fun find(pattern: String, from: String?): List<IncludedFile>
}

class IncludeException(message: String) : Exception(message)

/**
 * The journal with its include directives replaced by the files they name, as one text for the parser,
 * and where each of its lines came from: [files] (index 0 is the main file, named null) and the line in
 * that file. [problems] are includes that couldn't be read, at their line in the main file.
 */
class Expanded(
    val text: String,
    private val fileOf: IntArray,
    private val lineOf: IntArray,
    val files: List<String?>,
    val problems: List<JournalIssue>,
) {
    val hasIncludes: Boolean get() = files.size > 1

    /** The file (null for the main one) and line a line of [text] came from. */
    fun origin(line: Int): Pair<String?, Int> {
        if (line < 1 || line > fileOf.size) return null to line
        return files[fileOf[line - 1]] to lineOf[line - 1]
    }
}

/**
 * hledger's include directive, read by expanding the included files in place. Directives that only last
 * to the end of a file in hledger (apply account, comment blocks) are closed at the end of each included
 * file, so they don't carry on into the file that included it.
 */
object Includes {
    private const val MAX_DEPTH = 10
    private val INCLUDE = Regex("^!?include\\s+(.+)$")

    /** True if [text] has any include directives (outside comment blocks). */
    fun mentioned(text: String): Boolean = splitLines(text).any { INCLUDE.matches(it.trimEnd()) }

    fun expand(main: String, resolver: IncludeResolver): Expanded {
        val out = StringBuilder()
        val fileOf = ArrayList<Int>()
        val lineOf = ArrayList<Int>()
        val files = arrayListOf<String?>(null)
        val problems = ArrayList<JournalIssue>()
        var mainLine = 0 // the main file's line being expanded, for problems

        fun emit(text: String, file: Int, line: Int) {
            out.append(text).append('\n')
            fileOf.add(file); lineOf.add(line)
        }

        fun run(text: String, fileIndex: Int, chain: List<String?>, depth: Int) {
            var inComment = false
            var applyDepth = 0
            for ((i, raw) in splitLines(text.removePrefix("\uFEFF")).withIndex()) {
                val lineNo = i + 1
                if (fileIndex == 0) mainLine = lineNo
                val line = raw.trimEnd('\r')
                val t = line.trimEnd()
                if (inComment) {
                    if (t == "end comment") inComment = false
                    emit(line, fileIndex, lineNo); continue
                }
                if (t == "comment") inComment = true
                if (t.startsWith("apply account ")) applyDepth++
                if (t.startsWith("end apply account") && applyDepth > 0) applyDepth--
                val m = INCLUDE.matchEntire(t)
                if (m == null) {
                    emit(line, fileIndex, lineNo); continue
                }
                val pattern = m.groupValues[1].substringBefore(" ;").trim()
                // The directive stays, as a comment, so the line numbers still match.
                emit("; $t", fileIndex, lineNo)
                val from = files[fileIndex]
                val found = try {
                    resolver.find(pattern, from)
                } catch (e: IncludeException) {
                    problems.add(JournalIssue(mainLine, "include $pattern: ${e.message}")); continue
                }
                if (found.isEmpty()) {
                    problems.add(JournalIssue(mainLine, "include $pattern: no such file" + (from?.let { " (from $it)" } ?: "")))
                    continue
                }
                for (f in found) {
                    when {
                        f.name in chain -> problems.add(JournalIssue(mainLine, "include ${f.name}: it includes itself, so it's read once"))
                        depth >= MAX_DEPTH -> problems.add(JournalIssue(mainLine, "include ${f.name}: includes go more than $MAX_DEPTH deep"))
                        else -> {
                            val idx = files.size
                            files.add(f.name)
                            run(f.text, idx, chain + f.name, depth + 1)
                            // Close what the included file left open, at the include's line.
                            emit("", fileIndex, lineNo)
                        }
                    }
                }
            }
            if (fileIndex != 0) {
                if (inComment) emit("end comment", fileIndex, 0)
                repeat(applyDepth) { emit("end apply account", fileIndex, 0) }
            }
        }

        run(main, 0, listOf(null), 0)
        return Expanded(out.toString(), fileOf.toIntArray(), lineOf.toIntArray(), files, problems)
    }

    /**
     * [journal] parsed from an [Expanded] text, with every line number put back in terms of its own file,
     * and transactions, rules and warnings from included files marked with the file's name.
     */
    fun remap(journal: Journal, x: Expanded): Journal {
        fun ln(l: Int) = x.origin(l).second
        fun fileOf(l: Int) = x.origin(l).first
        fun assertion(a: BalanceAssertion?) = a?.copy(line = ln(a.line))
        val transactions = journal.transactions.map { t ->
            if (t.forecast) t.copy(line = ln(t.line), lastLine = ln(t.lastLine), file = fileOf(t.line))
            else t.copy(
                line = ln(t.line), lastLine = ln(t.lastLine), file = fileOf(t.line),
                postings = t.postings.map { p -> p.copy(line = if (p.line > 0) ln(p.line) else 0, assertion = assertion(p.assertion)) },
            )
        }
        return journal.copy(
            transactions = transactions,
            periodicRules = journal.periodicRules.map { r ->
                r.copy(line = ln(r.line), lastLine = ln(r.lastLine), file = fileOf(r.line),
                    postings = r.postings.map { it.copy(line = ln(it.line)) })
            },
            autoRules = journal.autoRules.map { r -> r.copy(line = ln(r.line), file = fileOf(r.line), postings = r.postings.map { it.copy(line = ln(it.line)) }) },
            prices = journal.prices.map { it.copy(line = ln(it.line)) },
            warnings = x.problems + journal.warnings.map { w -> w.copy(line = ln(w.line), file = fileOf(w.line)) },
            includedFiles = x.files.filterNotNull(),
        )
    }
}
