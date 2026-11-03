package app.ledgedroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.AlertSettings
import app.ledgedroid.core.AccountType
import app.ledgedroid.core.BarChartData
import app.ledgedroid.core.Charts
import app.ledgedroid.core.Entry
import app.ledgedroid.core.EntrySuggestions
import app.ledgedroid.core.Health
import app.ledgedroid.core.HealthKind
import app.ledgedroid.core.Journal
import app.ledgedroid.core.LineChartData
import app.ledgedroid.core.Payees
import app.ledgedroid.core.Reconcile
import app.ledgedroid.core.Schedule
import app.ledgedroid.core.SharePattern
import app.ledgedroid.core.SharedEntry
import app.ledgedroid.core.Template
import app.ledgedroid.core.Valuation
import app.ledgedroid.core.ValueTarget
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ====================================================================== the change preview

/**
 * A change across the journal, before it's made: every line it changes (before, crossed out, and after),
 * what matches but can't be changed here and why, and Apply (with Undo afterwards) or Cancel.
 */
@Composable
fun EditPreviewDialog(pending: LedgerViewModel.PendingEdit, onApply: () -> Unit, onCancel: () -> Unit) {
    val lc = LocalLedgerColors.current
    val plan = pending.plan
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(plan.what) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (val n = plan.changes.size) {
                        0 -> "Nothing in the journal file needs changing."
                        1 -> "1 line of the journal changes:"
                        else -> "$n lines of the journal change:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                plan.changes.take(300).forEach { c ->
                    Column {
                        Text(if (c.before == null) "New line after line ${c.line}" else "Line ${c.line}", style = MaterialTheme.typography.labelSmall, color = lc.mutedInk)
                        if (c.before != null) {
                            Text(c.before.trim(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error, textDecoration = TextDecoration.LineThrough)
                        }
                        Text(c.after.trim().ifEmpty { "(blank line)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (plan.changes.size > 300) Text("…and ${plan.changes.size - 300} more", color = lc.mutedInk)
                plan.notChanged.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = lc.mutedInk) }
                if (!plan.isEmpty) Text("Undo is offered afterwards.", style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
            }
        },
        confirmButton = { TextButton(onClick = onApply, enabled = !plan.isEmpty) { Text("Change ${plan.changes.size}") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Type an account name, with the journal's accounts suggested; [detail] says what happens. */
@Composable
fun AccountNameDialog(
    title: String,
    initial: String,
    detail: String,
    confirm: String,
    suggestions: EntrySuggestions?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Account") },
                    isError = text.contains("  ") || text.contains('\t'),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
                if (focused) SuggestionList(suggestions?.accountsMatching(text, 5).orEmpty(), onPick = { text = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank() && text.trim() != initial && !text.contains("  ")) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose which of [payee]'s accounts to move, and where to: shown before anything changes. */
@Composable
fun RecategoriseDialog(journal: Journal, payee: String, suggestions: EntrySuggestions?, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val accounts = remember(journal, payee) {
        journal.transactions.filter { !it.forecast && Payees.of(it.description) == payee }.flatMap { it.postings }
            .groupingBy { it.account }.eachCount().entries.sortedWith(
                // The category side first (eg expenses), the money side (bank, card) last.
                compareBy<Map.Entry<String, Int>> { journal.accountType(it.key) in setOf(AccountType.ASSET, AccountType.CASH, AccountType.LIABILITY) }
                    .thenByDescending { it.value }
            ).map { it.key }
    }
    var from by remember { mutableStateOf(accounts.firstOrNull()) }
    var to by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recategorise $payee") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Move the postings to", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    accounts.forEach { a -> FilterChip(selected = a == from, onClick = { from = a }, label = { Text(a, maxLines = 1) }) }
                }
                Text("in every $payee transaction to", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = to, onValueChange = { to = it }, singleLine = true, label = { Text("Account") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                )
                if (focused) SuggestionList(suggestions?.accountsMatching(to, 5).orEmpty(), onPick = { to = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { from?.let { onConfirm(it, to.trim()) } }, enabled = from != null && to.isNotBlank() && to.trim() != from) { Text("Show changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ====================================================================== reconciling

/**
 * Reconciling an account against a statement: the statement's date and closing balance, the cleared
 * balance and the difference as postings are ticked off, and Save, which clears the ticked ones (and, when
 * it all adds up, can add a balance assertion so hledger checks it from then on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileScreen(
    journal: Journal,
    account: String,
    subaccounts: Boolean,
    onSave: (Reconcile, Set<Int>, BigDecimal?, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    val styles = journal.styles
    val commodities: List<String> = remember(journal, account) { Reconcile.commodities(journal, account, subaccounts) }
    var commodity: String by remember { mutableStateOf(commodities.firstOrNull() ?: "") }
    var date: LocalDate by remember { mutableStateOf(LocalDate.now()) }
    var balanceText: String by remember { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }
    val r: Reconcile = remember(journal, account, subaccounts, commodity, date) { Reconcile(journal, account, subaccounts, commodity, date) }
    var ticked: Set<Int> by remember(r) { mutableStateOf(r.initiallyTicked) }
    var assertion: Boolean by remember { mutableStateOf(true) }
    val statement: BigDecimal? = Entry.parseAmount(balanceText.trim())
    val cleared: BigDecimal = r.clearedBalance(ticked)
    val difference: BigDecimal? = statement?.let { r.difference(it, ticked) }
    val balanced = difference != null && difference.signum() == 0
    val target: app.ledgedroid.core.ReconcileItem? = if (balanced) r.assertionTarget(ticked) else null
    val (clear, unclear) = r.changes(ticked)

    Scaffold(
        topBar = { LedgerTopBar("Reconcile", onBack, subtitle = account + if (subaccounts) " and subaccounts" else "") },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            clear.size.takeIf { it > 0 }?.let { "$it to clear" },
                            unclear.size.takeIf { it > 0 }?.let { "$it to unmark" },
                            if (target != null && assertion) "assertion" else null,
                        ).joinToString(", ").ifEmpty { "No changes yet" },
                        style = MaterialTheme.typography.bodyMedium, color = lc.mutedInk, modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { onSave(r, ticked, statement, assertion && target != null) },
                        enabled = clear.isNotEmpty() || unclear.isNotEmpty() || (assertion && target != null),
                    ) { Text("Save") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "statement") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (commodities.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        commodities.forEach { c -> FilterChip(selected = c == commodity, onClick = { commodity = c }, label = { Text(c.ifEmpty { "No currency" }) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { pickingDate = true }, label = { Text("Statement date: ${date.format(WEEKDAY_DATE)}") },
                            leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) })
                    }
                    OutlinedTextField(
                        value = balanceText, onValueChange = { balanceText = it }, singleLine = true,
                        label = { Text("Statement's closing balance" + if (commodity.isNotEmpty()) " ($commodity)" else "") },
                        textStyle = NumberStyle, isError = balanceText.isNotBlank() && statement == null,
                        supportingText = { Text("With the journal's sign: what you owe on a card is negative") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Surface(color = if (balanced) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row { Text("Cleared balance", Modifier.weight(1f)); Text(styles.format(commodity, cleared), style = NumberStyle) }
                            if (difference != null) Row {
                                Text(if (balanced) "Difference: none, it adds up" else "Difference", Modifier.weight(1f))
                                Text(styles.format(commodity, difference), style = NumberStyle, color = if (balanced) lc.rule else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (balanced) {
                        if (target != null) Row(Modifier.clickable { assertion = !assertion }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = assertion, onCheckedChange = { assertion = it })
                            Text("Add a balance assertion, = ${styles.format(commodity, statement!!)}, on ${target.transaction.date}, so hledger checks it from then on",
                                style = MaterialTheme.typography.bodySmall)
                        } else r.assertionProblem(ticked)?.let {
                            Text("No balance assertion can be added: $it.", style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
                        }
                    }
                }
                HorizontalDivider(color = lc.hairline)
            }
            if (r.items.isEmpty()) item { Text("No postings in $commodity up to ${date}.", Modifier.padding(24.dp), color = lc.mutedInk) }
            items(r.items.asReversed(), key = { it.key }) { item ->
                val on = item.key in ticked
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = item.locked == null) { ticked = if (on) ticked - item.key else ticked + item.key }
                        .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null, enabled = item.locked == null)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(item.transaction.description.ifEmpty { "(no description)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(item.transaction.date.format(WEEKDAY_DATE), item.posting.account.takeIf { subaccounts }, item.locked).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = lc.mutedInk, maxLines = 2)
                    }
                    Text(styles.format(commodity, item.quantity), style = NumberStyle)
                }
            }
        }
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.utcMillis())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = it.utcDate() }; pickingDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

// ====================================================================== journal health

/**
 * Journal health, like hledger check: accounts used but not declared, payees not declared (once any are),
 * dates out of order, failed balance assertions. Declarations are fixed with a tap (shown first, with
 * Undo); the others open the transaction to fix.
 */
@Composable
fun HealthScreen(journal: Journal, onDeclare: (List<String>, List<String>) -> Unit, onOpenTransaction: (Int) -> Unit, onBack: () -> Unit) {
    val lc = LocalLedgerColors.current
    val issues = remember(journal) { Health.check(journal) }
    Scaffold(topBar = { LedgerTopBar("Journal health", onBack, subtitle = if (issues.isEmpty()) "All checks pass" else "${issues.size} to look at") }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (issues.isEmpty()) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = lc.rule); Spacer(Modifier.width(8.dp)); Text("All checks pass", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Every account used is declared, " + (if (journal.declaredPayees.isEmpty()) "" else "every payee is declared, ") +
                            "dates are in order in each file, and balance assertions hold.", color = lc.mutedInk)
                    if (journal.declaredPayees.isEmpty()) Text("Payees are checked once the journal declares some (payee directives).",
                        style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
                }
            }
            for ((kind, group) in issues.groupBy { it.kind }) {
                item(key = "h:$kind") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${kind.title} (${group.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (group.size > 1) when (kind) {
                            HealthKind.UNDECLARED_ACCOUNT -> TextButton(onClick = { onDeclare(group.mapNotNull { it.name }, emptyList()) }) { Text("Declare all") }
                            HealthKind.UNDECLARED_PAYEE -> TextButton(onClick = { onDeclare(emptyList(), group.mapNotNull { it.name }) }) { Text("Declare all") }
                            else -> Unit
                        }
                    }
                }
                items(group, key = { "${it.kind}:${it.message}" }) { issue ->
                    val opens = issue.transactionIndex
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = opens != null) { opens?.let(onOpenTransaction) }.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Warning, null, tint = lc.mutedInk, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(issue.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        when (issue.kind) {
                            HealthKind.UNDECLARED_ACCOUNT -> TextButton(onClick = { onDeclare(listOfNotNull(issue.name), emptyList()) }) { Text("Declare") }
                            HealthKind.UNDECLARED_PAYEE -> TextButton(onClick = { onDeclare(emptyList(), listOfNotNull(issue.name)) }) { Text("Declare") }
                            else -> if (opens != null) Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================== charts

private val CHART_COLOURS = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFF59A14F), Color(0xFFE15759),
    Color(0xFFB07AA1), Color(0xFF76B7B2), Color(0xFF9C755F),
)
private val MONTH = DateTimeFormatter.ofPattern("MMM")

/**
 * Charts: net worth at the end of each month (a line), and spending per month by category (stacked bars).
 * Tap a month to see its numbers. With market prices in the journal, other commodities can be counted at
 * their market value.
 */
@Composable
fun ChartsScreen(journal: Journal, onBack: () -> Unit) {
    val lc = LocalLedgerColors.current
    var months by remember { mutableIntStateOf(12) }
    var valued by remember { mutableStateOf(journal.prices.isNotEmpty()) }
    val commodity = remember(journal) { Charts.mainCommodity(journal) }
    val valuation = if (valued && journal.prices.isNotEmpty()) Valuation(journal.prices, ValueTarget(commodity)) else null
    val today = LocalDate.now()
    val net = remember(journal, months, valued) { Charts.netWorth(journal, today, months, valuation, commodity) }
    val spending = remember(journal, months, valued) { Charts.spending(journal, today, months, valuation = valuation, commodity = commodity) }
    var selected by remember(months) { mutableIntStateOf(net.values.size - 1) }
    Scaffold(topBar = { LedgerTopBar("Charts", onBack, subtitle = commodity.ifEmpty { null }?.let { "In $it" }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(6, 12, 24).forEachIndexed { i, m ->
                    SegmentedButton(selected = months == m, onClick = { months = m }, shape = SegmentedButtonDefaults.itemShape(i, 3), icon = {}) { Text("$m months") }
                }
            }
            if (journal.prices.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { valued = !valued }, verticalAlignment = Alignment.CenterVertically) {
                Text("Count other commodities at market value (P prices)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = valued, onCheckedChange = { valued = it })
            }
            Text("Net worth", style = MaterialTheme.typography.titleMedium)
            LineChart(net, selected) { selected = it }
            net.values.getOrNull(selected)?.let { v ->
                Text("${net.periods[selected].start!!.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}: ${journal.styles.format(commodity, v)}",
                    style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(color = lc.hairline)
            Text("Spending by category", style = MaterialTheme.typography.titleMedium)
            if (spending.categories.isEmpty()) Text("No expenses in these months.", color = lc.mutedInk)
            else {
                BarChart(spending, selected) { selected = it }
                spending.categories.forEachIndexed { i, (name, values) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(CHART_COLOURS[i % CHART_COLOURS.size], CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        values.getOrNull(selected)?.let { Text(journal.styles.format(commodity, it), style = NumberStyle) }
                    }
                }
                spending.periods.getOrNull(selected)?.let { p ->
                    Text("${p.start!!.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}: ${journal.styles.format(commodity, spending.total(selected))} in all",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (valuation == null && journal.transactions.any { t -> t.postings.any { it.amount.commodity != commodity } }) {
                Text("Amounts in other commodities aren't counted" + if (journal.prices.isEmpty()) " (the journal has no P prices to convert them)." else ".",
                    style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
            }
        }
    }
}

/** Month labels along the bottom, a few of them. */
@Composable
private fun MonthLabels(periods: List<app.ledgedroid.core.DateSpan>) {
    val step = if (periods.size > 12) 3 else if (periods.size > 6) 2 else 1
    Row(Modifier.fillMaxWidth()) {
        periods.forEachIndexed { i, p ->
            Text(if (i % step == 0) p.start!!.format(MONTH) else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = LocalLedgerColors.current.mutedInk, maxLines = 1)
        }
    }
}

@Composable
private fun LineChart(data: LineChartData, selected: Int, onSelect: (Int) -> Unit) {
    val lc = LocalLedgerColors.current
    val line = MaterialTheme.colorScheme.primary
    val values = data.values.map { it.toDouble() }
    if (values.isEmpty()) return
    val lo = minOf(0.0, values.min()); val hi = maxOf(0.0, values.max()).let { if (it == lo) lo + 1 else it }
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(values.size) {
            detectTapGestures { o -> onSelect(((o.x / size.width) * values.size).toInt().coerceIn(0, values.size - 1)) }
        }) {
            val w = size.width / values.size
            fun x(i: Int) = w * i + w / 2
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
            drawLine(lc.hairline, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, line, style = Stroke(width = 5f))
            values.forEachIndexed { i, v -> drawCircle(if (i == selected) lc.ink else line, radius = if (i == selected) 9f else 5f, center = Offset(x(i), y(v))) }
        }
        MonthLabels(data.periods)
    }
}

@Composable
private fun BarChart(data: BarChartData, selected: Int, onSelect: (Int) -> Unit) {
    val lc = LocalLedgerColors.current
    val n = data.periods.size
    val totals = (0 until n).map { i -> data.categories.sumOf { (_, v) -> v[i].toDouble().coerceAtLeast(0.0) } }
    val hi = (totals.maxOrNull() ?: 0.0).let { if (it <= 0) 1.0 else it }
    Column {
        Canvas(Modifier.fillMaxWidth().height(200.dp).pointerInput(n) {
            detectTapGestures { o -> onSelect(((o.x / size.width) * n).toInt().coerceIn(0, n - 1)) }
        }) {
            val w = size.width / n
            for (i in 0 until n) {
                var top = size.height
                data.categories.forEachIndexed { c, (_, v) ->
                    val h = (v[i].toDouble().coerceAtLeast(0.0) / hi * size.height).toFloat()
                    drawRect(CHART_COLOURS[c % CHART_COLOURS.size], topLeft = Offset(w * i + w * 0.15f, top - h),
                        size = androidx.compose.ui.geometry.Size(w * 0.7f, h), alpha = if (i == selected) 1f else 0.75f)
                    top -= h
                }
                if (i == selected) drawRect(lc.ink, topLeft = Offset(w * i + w * 0.1f, top - 6f), size = androidx.compose.ui.geometry.Size(w * 0.8f, 4f))
            }
        }
        MonthLabels(data.periods)
    }
}

// ====================================================================== share patterns

/**
 * The person's patterns for reading shared text, tried in order before the built-in reading, with a box
 * to try text against them and see what each would pick out.
 */
@Composable
fun SharePatternsScreen(
    patterns: List<SharePattern>,
    templates: List<Template>,
    onChange: (List<SharePattern>) -> Unit,
    read: (String, List<SharePattern>) -> SharedEntry,
    onBack: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    var sample by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<SharePattern?>(null) }
    Scaffold(
        topBar = { LedgerTopBar("Share patterns", onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = SharePattern("p" + System.currentTimeMillis(), "", "") }) { Icon(Icons.Default.Add, "Add a pattern") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Share a bank message, a notification's text or a note to Ledgedroid to start quick add with the amount, " +
                        "date and payee filled in. Your patterns are tried first, in order, then Ledgedroid's own reading of common wording.",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("A pattern is a regular expression (ignoring case) whose named groups pick things out: (?<amount>…), (?<currency>…), " +
                        "(?<payee>…), (?<date>…), (?<account>…). For example: spent (?<amount>[0-9.,]+) (?<currency>[A-Z]{3}) at (?<payee>.+?) on",
                    style = MaterialTheme.typography.bodySmall, color = lc.mutedInk, fontFamily = FontFamily.Default)
            }
            item {
                OutlinedTextField(value = sample, onValueChange = { sample = it }, label = { Text("Try it: paste a message") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                if (sample.isNotBlank()) {
                    val e = read(sample, patterns)
                    Text(
                        (if (e.matchedBy != null) "Pattern \"${e.matchedBy}\": " else "Built-in reading: ") + listOfNotNull(
                            e.amount?.let { "amount ${e.hints().amount}" }, e.payee?.let { "payee $it" }, e.date?.let { "date $it" },
                            e.account?.let { "account $it" }, e.templateId?.let { id -> "template ${templates.firstOrNull { it.id == id }?.name ?: id}" },
                        ).joinToString(", ").ifEmpty { "nothing found" },
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (patterns.isEmpty()) item { Text("No patterns yet: the built-in reading is used.", color = lc.mutedInk) }
            items(patterns, key = { it.id }) { p ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifEmpty { "(no name)" }, style = MaterialTheme.typography.bodyLarge)
                            Text(p.regex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = lc.mutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            p.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        Switch(checked = p.enabled, onCheckedChange = { on -> onChange(patterns.map { if (it.id == p.id) it.copy(enabled = on) else it }) })
                        val i = patterns.indexOf(p)
                        if (i > 0) IconButton(onClick = { onChange(patterns.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Icon(Icons.Default.KeyboardArrowUp, "Try it earlier") }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { onChange(patterns - p) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    editing?.let { p ->
        SharePatternDialog(p, templates, sample, read, onSave = { saved ->
            onChange(if (patterns.any { it.id == saved.id }) patterns.map { if (it.id == saved.id) saved else it } else patterns + saved)
            editing = null
        }, onDismiss = { editing = null })
    }
}

@Composable
private fun SharePatternDialog(
    pattern: SharePattern,
    templates: List<Template>,
    sample: String,
    read: (String, List<SharePattern>) -> SharedEntry,
    onSave: (SharePattern) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(pattern.name) }
    var regex by remember { mutableStateOf(pattern.regex) }
    var templateId by remember { mutableStateOf(pattern.templateId) }
    val draft = pattern.copy(name = name.trim(), regex = regex, templateId = templateId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pattern.regex.isEmpty()) "New pattern" else "Edit pattern") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name, eg My bank") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = regex, onValueChange = { regex = it }, label = { Text("Pattern") }, minLines = 2,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = regex.isNotEmpty() && draft.problem != null, supportingText = { (draft.problem.takeIf { regex.isNotEmpty() })?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth())
                if (templates.isNotEmpty()) {
                    Text("When it matches, start from a template:", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = templateId == null, onClick = { templateId = null }, label = { Text("None") })
                        templates.forEach { t -> FilterChip(selected = templateId == t.id, onClick = { templateId = t.id }, label = { Text(t.name) }) }
                    }
                }
                if (sample.isNotBlank() && draft.problem == null) {
                    val e = read(sample, listOf(draft))
                    Text(if (e.matchedBy == null) "Doesn't match the text being tried." else "Matches the text being tried: " +
                            listOfNotNull(e.amount?.let { "amount ${e.hints().amount}" }, e.payee?.let { "payee $it" }, e.date?.let { "date $it" }, e.account?.let { "account $it" })
                                .joinToString(", ").ifEmpty { "nothing picked out" },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = draft.problem == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ====================================================================== reminders

/** When [template]'s transaction recurs, to be reminded on the day; or no reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(template: Template, onSave: (Schedule?) -> Unit, onDismiss: () -> Unit) {
    val s = template.schedule
    var every by remember { mutableStateOf((s?.every ?: 1).toString()) }
    var unit by remember { mutableStateOf(s?.unit ?: Schedule.Unit.MONTHS) }
    var start by remember { mutableStateOf(s?.start ?: LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    val n = every.toIntOrNull()?.takeIf { it in 1..365 }
    val schedule = n?.let { Schedule(it, unit, start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me: ${template.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Every")
                    OutlinedTextField(value = every, onValueChange = { every = it.filter(Char::isDigit).take(3) }, singleLine = true,
                        isError = n == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(72.dp))
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Schedule.Unit.entries.forEachIndexed { i, u ->
                        SegmentedButton(selected = unit == u, onClick = { unit = u }, shape = SegmentedButtonDefaults.itemShape(i, 3), icon = {}) {
                            Text(u.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                AssistChip(onClick = { picking = true }, label = { Text("From ${start.format(WEEKDAY_DATE)}") },
                    leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) })
                schedule?.let {
                    Text("${it.describe()}. Next: ${it.nextAfter(LocalDate.now().minusDays(1)).format(WEEKDAY_DATE)}.", style = MaterialTheme.typography.bodySmall)
                }
                Text("A notification comes on the day, unless a ${template.draft.payee.ifEmpty { template.name }} transaction is already in the journal. " +
                        "Tap it to add the transaction from the template.", style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(schedule) }, enabled = schedule != null) { Text("Save") } },
        dismissButton = {
            Row {
                if (s != null) TextButton(onClick = { onSave(null) }) { Text("No reminder") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = start.utcMillis())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { start = it.utcDate() }; picking = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

// ====================================================================== settings sections

/** Settings › Included files: the folder include directives are read from. */
@Composable
fun IncludedFilesSection(folder: String?, included: List<String>, mentionsIncludes: Boolean, onChoose: () -> Unit, onForget: () -> Unit) {
    val lc = LocalLedgerColors.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("A journal can include other files (include 2026.journal). Android only lets Ledgedroid read files it's given, " +
                "so choose the folder the journal is in (or one above it) to read them. Included files are read, not changed: " +
                "edit their transactions where they are.", style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
        Text(
            when {
                folder == null && mentionsIncludes -> "This journal includes other files, which aren't read yet."
                folder == null -> "No folder chosen."
                included.isEmpty() -> "Folder: ${app.ledgedroid.IncludeFolder.name(android.net.Uri.parse(folder))}"
                else -> "Folder: ${app.ledgedroid.IncludeFolder.name(android.net.Uri.parse(folder))}. Reading ${included.size} included file${if (included.size == 1) "" else "s"}: ${included.joinToString()}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onChoose) { Text(if (folder == null) "Choose folder…" else "Choose another…") }
            if (folder != null) TextButton(onClick = onForget) { Text("Stop reading them") }
        }
    }
}

/** Settings › Notifications: reminders for recurring transactions, and budget alerts. */
@Composable
fun NotificationsSection(s: AlertSettings, allowed: Boolean, scheduled: Int, onChange: (AlertSettings) -> Unit) {
    val lc = LocalLedgerColors.current
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchLine("Reminders for recurring transactions",
            if (scheduled == 0) "Set a schedule on a template (Templates › ⋮ › Remind me…)." else "$scheduled template${if (scheduled == 1) " has" else "s have"} a schedule.",
            s.reminders) { onChange(s.copy(reminders = it)) }
        SwitchLine("Budget alerts", "When spending reaches ${s.budgetWarnAt}% of a monthly budget (from the journal's ~ rules)" +
                if (s.budgetWarnAt < 100) ", and again once it's over." else ".", s.budget) { onChange(s.copy(budget = it)) }
        if (s.budget) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(75, 90, 100).forEach { p -> FilterChip(selected = s.budgetWarnAt == p, onClick = { onChange(s.copy(budgetWarnAt = p)) }, label = { Text("At $p%") }) }
        }
        if (s.any && !allowed) Text("Notifications are turned off for Ledgedroid in Android's settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("Checked a few times a day, and when the app opens.", style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalLedgerColors.current.mutedInk)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
