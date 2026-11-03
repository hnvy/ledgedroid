package app.ledgedroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.AccountListMode
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.Entry
import app.ledgedroid.core.PeriodPreset
import app.ledgedroid.core.ReportKind
import app.ledgedroid.core.Styles
import app.ledgedroid.core.Template
import app.ledgedroid.core.Schedule
import app.ledgedroid.core.describeSpan

/** The list of templates: tap to add a transaction from one, or edit, reorder and delete them. */
@Composable
fun TemplatesScreen(
    templates: List<Template>,
    styles: Styles,
    onBack: () -> Unit,
    onUse: (Template) -> Unit,
    onEdit: (String?) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    /** Set or clear when the template's transaction recurs, for reminders. */
    onSchedule: (String, Schedule?) -> Unit = { _, _ -> },
) {
    var deleting by remember { mutableStateOf<Template?>(null) }
    var scheduling by remember { mutableStateOf<Template?>(null) }
    scheduling?.let { t -> ScheduleDialog(t, onSave = { s -> onSchedule(t.id, s); scheduling = null }, onDismiss = { scheduling = null }) }
    Scaffold(
        topBar = { LedgerTopBar("Templates", onBack, subtitle = "For regular transactions") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New template") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (templates.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(24.dp)) {
                        Text("No templates yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A template fills in the form for something you buy often, like coffee or fuel. " +
                                    "Create one here, or use Templates › Save this as a template in the add form. " +
                                    "The first three also appear on the home screen widget.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalLedgerColors.current.mutedInk,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            itemsIndexed(templates, key = { _, t -> t.id }) { i, t ->
                ManageRow(
                    title = t.name,
                    detail = templateSummary(t, styles) + (t.schedule?.let { "\n" + it.describe() } ?: ""),
                    onClick = { onUse(t) },
                    canMoveUp = i > 0,
                    canMoveDown = i < templates.lastIndex,
                    onMove = { by -> onMove(t.id, by) },
                    menu = listOf(
                        "Add transaction" to { onUse(t) },
                        "Edit" to { onEdit(t.id) },
                        (if (t.schedule == null) "Remind me…" else "Reminder…") to { scheduling = t },
                        "Delete" to { deleting = t }),
                )
            }
        }
    }
    deleting?.let { t ->
        ConfirmDelete("Delete the template \"${t.name}\"?", onDismiss = { deleting = null }) {
            deleting = null; onDelete(t.id)
        }
    }
}

private fun templateSummary(t: Template, styles: Styles): String {
    val d = t.draft
    val parts = d.used.map { i ->
        val p = d.postings[i]
        val amount = Entry.parseAmount(p.amount)?.let { " " + Entry.formatExact(p.commodity.trim(), it, styles) } ?: ""
        p.account.trim().ifEmpty { "?" } + amount
    }
    return listOfNotNull(d.description.ifEmpty { null }, parts.joinToString(", ").ifEmpty { null }).joinToString(" · ")
}

/** Saved reports: tap one to show it. */
@Composable
fun SavedViewsScreen(
    views: List<SavedView>,
    current: Options,
    onBack: () -> Unit,
    onApply: (SavedView) -> Unit,
    onSaveCurrent: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SavedView?>(null) }
    var deleting by remember { mutableStateOf<SavedView?>(null) }
    Scaffold(
        topBar = { LedgerTopBar("Saved reports", onBack, subtitle = "Reports and searches you use often") },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { naming = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Save current report") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "current") {
                Text(
                    "Showing now: " + describeOptions(current),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLedgerColors.current.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (views.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "Set up a report the way you like it, with its period, options and search, then save it here to come back to it with one tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalLedgerColors.current.mutedInk,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            itemsIndexed(views, key = { _, v -> v.id }) { _, v ->
                ManageRow(
                    title = v.name,
                    detail = describeOptions(v.options),
                    onClick = { onApply(v) },
                    menu = listOf(
                        "Show" to { onApply(v) },
                        "Replace with current report" to { onUpdate(v.id) },
                        "Rename" to { renaming = v },
                        "Delete" to { deleting = v },
                    ),
                )
            }
        }
    }
    if (naming) {
        NameDialog("Save current report", "", "Save", onDismiss = { naming = false }) {
            naming = false; onSaveCurrent(it)
        }
    }
    renaming?.let { v ->
        NameDialog("Rename", v.name, "Rename", onDismiss = { renaming = null }) { renaming = null; onRename(v.id, it) }
    }
    deleting?.let { v ->
        ConfirmDelete("Delete the saved report \"${v.name}\"?", onDismiss = { deleting = null }) {
            deleting = null; onDelete(v.id)
        }
    }
}

/**
 * A one-line summary of a report's settings, as in effect with the options typed into the filter, eg
 * "Budget · Monthly · This year · filter: --budget -M food".
 */
fun describeOptions(stored: Options): String {
    val o = stored.resolve().options
    return buildList {
        add(
            when (o.kind) {
                ReportKind.BALANCE -> "Accounts"
                ReportKind.BALANCE_SHEET -> "Balance sheet"
                ReportKind.INCOME_STATEMENT -> "Income"
                ReportKind.BUDGET -> "Budget"
            }
        )
        if (o.kind == ReportKind.BUDGET || o.multiPeriod) {
            add(o.interval.label + if (o.periodTable) "" else ", one at a time")
        }
        add(if (o.preset == PeriodPreset.CUSTOM) describeSpan(DateSpan(o.customStart, o.customEnd)) else o.preset.label)
        o.depth?.let { add(if (it == 1) "1 level" else "$it levels") }
        if (o.mode == AccountListMode.FLAT) add("list")
        if (o.auto) add("auto postings")
        if (o.endBalance && (o.kind == ReportKind.BALANCE || o.kind == ReportKind.BUDGET)) add("running balances")
        if (o.showEmpty) add("-E")
        if (o.sortByAmount && o.kind != ReportKind.BUDGET) add("by amount")
        if (o.atCost) add("at cost")
        if (o.invert) add("inverted")
        if (stored.query.isNotBlank()) add("filter: ${stored.query}")
    }.joinToString(" · ")
}

@Composable
private fun ManageRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    menu: List<Pair<String, () -> Unit>>,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMove: ((Int) -> Unit)? = null,
) {
    val lc = LocalLedgerColors.current
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(lc.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = lc.mutedInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onMove != null) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    "Move down"
                )
            }
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                }
            }
        }
    }
}

@Composable
private fun ConfirmDelete(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
