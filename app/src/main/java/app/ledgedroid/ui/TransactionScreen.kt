package app.ledgedroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.Entry
import app.ledgedroid.core.Journal
import app.ledgedroid.core.Payees
import app.ledgedroid.core.Styles
import app.ledgedroid.core.Posting
import app.ledgedroid.core.PostingKind
import app.ledgedroid.core.Status
import app.ledgedroid.core.Transaction
import app.ledgedroid.core.tagsIn

/** A whole transaction: header, every posting with its amount and comment, tags, and the journal text. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TransactionScreen(
    journal: Journal?,
    index: Int,
    sourceLines: List<String>?,
    onBack: () -> Unit,
    onRepeat: (Transaction) -> Unit,
    onOpenPayee: (String) -> Unit,
    onOpenAccount: (String) -> Unit,
    onEdit: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit,
    onStatus: (Transaction, Status) -> Unit,
) {
    val t = journal?.transactions?.firstOrNull { it.index == index }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cantEdit by remember { mutableStateOf<List<String>?>(null) }
    val source = t?.let { tx -> sourceLines?.let { sourceText(it, tx) } }
    // Auto postings aren't in the file, so the transaction can't be changed while they're shown.
    val autoShown = journal?.autoApplied == true && t?.postings?.any { it.generated } == true
    // A --forecast transaction comes from a periodic rule and isn't in the file at all.
    val forecast = t?.forecast == true

    if (confirmDelete && t != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("${t.description.ifEmpty { "(no description)" }} on ${t.date.format(WEEKDAY_DATE)} will be removed from the journal file.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(t) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    cantEdit?.let { problems ->
        AlertDialog(
            onDismissRequest = { cantEdit = null },
            title = { Text("Edit it in a text editor") },
            text = { Text("This transaction has ${problems.joinToString(", ")}, which Ledgedroid can't change without losing.") },
            confirmButton = { TextButton(onClick = { cantEdit = null }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            LedgerTopBar(
                title = t?.let { Payees.of(it.description).ifEmpty { "Transaction" } } ?: "Transaction",
                subtitle = t?.date?.format(WEEKDAY_DATE),
                onBack = onBack,
                actions = {
                    if (source != null) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(source)); copied = true }) {
                            Text(if (copied) "Copied" else "Copy")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (journal == null || t == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (journal == null) "Loading…" else "This transaction is no longer in the journal.",
                    color = LocalLedgerColors.current.mutedInk,
                )
            }
            return@Scaffold
        }
        val lc = LocalLedgerColors.current
        val styles = journal.styles
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(t.description.ifEmpty { "(no description)" }, style = MaterialTheme.typography.headlineSmall)
                val facts = buildList {
                    if (t.code.isNotEmpty()) add("Code ${t.code}")
                    t.date2?.let { add("Secondary date ${it.format(WEEKDAY_DATE)}") }
                    if (forecast) add("Forecast from the rule on line ${t.line}")
                    else if (t.line > 0) add("Line ${t.line}")
                }
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = lc.mutedInk)
                }
                // One tap to mark it, eg while checking against a bank statement. Only the mark on the
                // first line changes, so this works even when the transaction can't be edited in the form.
                val canMark = source != null && !autoShown && !forecast
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        Status.UNMARKED to "Unmarked",
                        Status.PENDING to "Pending !",
                        Status.CLEARED to "Cleared *"
                    )
                    options.forEachIndexed { i, (s, label) ->
                        SegmentedButton(
                            selected = t.status == s,
                            onClick = { onStatus(t, s) },
                            enabled = canMark || t.status == s,
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                if (t.comment.isNotBlank()) Comment(t.comment)
            }

            val payee = Payees.of(t.description)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!forecast) OutlinedButton(onClick = {
                    val problems = Entry.editProblems(t, source) +
                            if (autoShown) listOf("auto postings shown (turn off Apply auto postings in Options)") else emptyList()
                    if (problems.isEmpty()) onEdit(t) else cantEdit = problems
                }) {
                    Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit")
                }
                if (!forecast) OutlinedButton(onClick = {
                    if (source == null || autoShown) cantEdit =
                        if (autoShown) listOf("auto postings shown (turn off Apply auto postings in Options)")
                        else listOf("its place in the file isn't known")
                    else confirmDelete = true
                }) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete")
                }
                OutlinedButton(onClick = { onRepeat(t) }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add again today")
                }
                if (payee.isNotEmpty()) OutlinedButton(onClick = { onOpenPayee(payee) }) { Text("All from $payee") }
            }

            SectionTitle("Postings")
            t.postings.forEach { p -> PostingItem(p, styles, onClick = { onOpenAccount(p.account) }) }

            val tags = (tagsIn(t.comment) + t.postings.flatMap { tagsIn(it.comment) }).distinct()
            if (tags.isNotEmpty()) {
                SectionTitle("Tags")
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { (name, value) ->
                        SuggestionChip(onClick = {}, label = { Text(if (value.isEmpty()) name else "$name: $value") })
                    }
                }
            }

            if (source != null) {
                SectionTitle(
                    when {
                        forecast -> if (copied) "Periodic rule (copied)" else "Periodic rule"
                        copied -> "In the journal (copied)"
                        else -> "In the journal"
                    }
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(
                        source,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
            if (forecast) {
                Text(
                    "This is a forecast (--forecast in the filter), made from a periodic rule. It isn't in the " +
                            "journal; Add again today records it as a real transaction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (journal.autoApplied && t.postings.any { it.generated }) {
                Text(
                    "Postings marked auto were added by auto posting rules and aren't in the journal text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val lc = LocalLedgerColors.current
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawLine(lc.rule, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            }
            .padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun PostingItem(p: Posting, styles: Styles, onClick: () -> Unit) {
    val lc = LocalLedgerColors.current
    val account = when (p.kind) {
        PostingKind.REAL -> p.account
        PostingKind.VIRTUAL -> "(${p.account})"
        PostingKind.BALANCED_VIRTUAL -> "[${p.account}]"
    }
    val notes = buildList {
        if (p.status == Status.CLEARED) add("cleared")
        if (p.status == Status.PENDING) add("pending")
        if (p.generated) add("auto")
        if (p.inferred) add("calculated")
        p.amount.cost?.let { c ->
            add(
                (if (c.isTotal) "total cost " else "at ") + styles.format(
                    c.commodity,
                    c.quantity
                )
            )
        }
        p.assertion?.let { a -> add("balance should be " + styles.format(a.amount.commodity, a.amount.quantity)) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(lc.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                account,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (p.generated) FontStyle.Italic else FontStyle.Normal
            )
            if (notes.isNotEmpty()) Text(
                notes.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = lc.mutedInk
            )
            if (p.comment.isNotBlank()) Comment(p.comment)
        }
        AmountLines(p.amount.toMixed(), styles)
    }
}

@Composable
private fun Comment(text: String) {
    Text(
        text.lines().joinToString("\n") { "; $it" },
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = LocalLedgerColors.current.mutedInk,
        modifier = Modifier.padding(top = 2.dp),
    )
}


/** The transaction's lines in the journal file. */
private fun sourceText(lines: List<String>, t: Transaction): String? {
    // Lines of an included file aren't the main file's: no text to show, nothing to change here.
    if (t.file != null) return null
    if (t.line <= 0 || t.line > lines.size) return null
    return lines.subList(t.line - 1, t.lastLine.coerceIn(t.line, lines.size)).joinToString("\n").trimEnd()
}
