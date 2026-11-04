package app.ledgedroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.AccountListMode
import app.ledgedroid.core.AccountType
import app.ledgedroid.core.BudgetCell
import app.ledgedroid.core.BudgetReport
import app.ledgedroid.core.BudgetRow
import app.ledgedroid.core.Budgets
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.Journal
import app.ledgedroid.core.ReportInterval
import app.ledgedroid.core.Styles
import app.ledgedroid.core.UNBUDGETED
import app.ledgedroid.core.describeSpan
import java.time.format.DateTimeFormatter

private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
private val DAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val DAY_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

private val NAME_WIDTH = 148.dp
private val CELL_WIDTH = 120.dp

/** The budget report: one period at a time with goal bars (swipe between them), or every period as a table. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BudgetContent(
    report: BudgetReport,
    journal: Journal,
    options: Options,
    rulePattern: String,
    collapsed: Set<String>,
    onToggleCollapsed: (String) -> Unit,
    onOpenRegister: (RegisterTarget) -> Unit,
    canWidenPeriod: Boolean,
    onShowAllDates: () -> Unit,
    onClearFilter: () -> Unit,
    onLongPress: (String) -> Unit,
    pinned: List<PinnedLine> = emptyList(),
    /** Pinned accounts left out for being at 0, to say so over the pinned ones. */
    pinnedAtZero: Int = 0,
) {
    val styles = journal.styles
    val tree = options.mode == AccountListMode.TREE
    val visible = visibleRowsOf(report.rows, tree, collapsed, "budget/", { it.indent }, { it.fullName })

    fun target(row: BudgetRow, span: DateSpan): RegisterTarget? {
        if (row.fullName == UNBUDGETED) return null
        return RegisterTarget(row.fullName.removePrefix("$UNBUDGETED:"), includeSubaccounts = true, section = null, span = span)
    }

    if (report.periods.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            BudgetNotice(report, journal, rulePattern)
            Text(
                "No transactions in this period.",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalLedgerColors.current.mutedInk,
                modifier = Modifier.padding(24.dp),
            )
            if (canWidenPeriod) {
                OutlinedButton(onClick = onShowAllDates, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Show all dates") }
            }
        }
        return
    }

    if (options.periodTable) {
        val whole = DateSpan(report.periods.first().start, report.periods.last().end)
        BudgetTable(report, journal, options, rulePattern, visible, onToggleCollapsed, onOpenRegister, onLongPress, pinned, pinnedAtZero) { row ->
            target(row, whole)
        }
        return
    }

    val pins = pinned.filter { it.budget != null }
    val pager = rememberPeriodPagerState(report.periods)
    Column(Modifier.fillMaxSize()) {
        BudgetNotice(report, journal, rulePattern)
        PagerStepper(pager) { i -> periodLabel(report.periods[i], options.interval, options.endBalance) }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top,
            key = { it },
        ) { index ->
            val period = report.periods[index]
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (showPinned(pins, pinnedAtZero)) {
                    item(key = "pin-header") { PinnedHeader(pinnedAtZero) }
                    items(pins, key = { "pin/${it.account}" }) { p ->
                        val row = p.budget!!
                        BudgetRowItem(
                            VisibleRow(row.copy(indent = 0, displayName = p.account), false, false, "pin/${p.account}"),
                            row.cells[index], styles, false, journal.accountType(p.account) == AccountType.EXPENSE,
                            onToggle = {},
                            onLongClick = { onLongPress(p.account) },
                            onClick = target(row, period)?.let { t -> { onOpenRegister(t) } },
                        )
                    }
                    item(key = "pin-end") { PinnedEnd() }
                }
                if (report.rows.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No accounts to show", style = MaterialTheme.typography.titleMedium)
                            if (options.query.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = onClearFilter) { Text("Clear filter") }
                            }
                        }
                    }
                }
                items(visible, key = { it.key }) { v ->
                    val overIsBad = journal.accountType(v.row.fullName) == AccountType.EXPENSE
                    BudgetRowItem(
                        v, v.row.cells[index], styles, tree, overIsBad,
                        onToggle = { onToggleCollapsed(v.key) },
                        onLongClick = { onLongPress(hideName(v.row.fullName)) },
                        onClick = target(v.row, period)?.let { t -> { onOpenRegister(t) } },
                    )
                }
                item(key = "total") { BudgetTotalRow(report.totals[index], styles) }
            }
        }
    }
}

/** The account to hide for a budget row: <unbudgeted>:expenses:x hides expenses:x everywhere. */
private fun hideName(fullName: String): String =
    if (fullName == UNBUDGETED) fullName else fullName.removePrefix("$UNBUDGETED:")

@Composable
private fun BudgetNotice(report: BudgetReport, journal: Journal, rulePattern: String) {
    val text = when {
        journal.periodicRules.isEmpty() ->
            "This journal has no budget goals yet. Goals come from periodic transaction rules like this one:"
        report.ruleCount == 0 ->
            "No budget rules have a description containing \"$rulePattern\". Change --budget=$rulePattern in the filter."
        else -> return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalLedgerColors.current.mutedInk)
        if (journal.periodicRules.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    "~ monthly\n    expenses:food     $400\n    assets:bank",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BudgetRowItem(
    v: VisibleRow<BudgetRow>,
    cell: BudgetCell,
    styles: Styles,
    tree: Boolean,
    overIsBad: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val lc = LocalLedgerColors.current
    val row = v.row
    val step = 16.dp
    val topLevel = tree && row.indent == 0
    val pinned = LocalTreeLook.current.isPinned(row.fullName)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClickLabel = "Pin or hide", onLongClick = onLongClick, onClick = onClick ?: {})
            .treeRow(
                indent = row.indent, step = step, guideStart = 24.dp, branch = branchColor(row.fullName),
                pinned = pinned, share = null, barStart = 0.dp, lc = lc,
            )
            .heightIn(min = 56.dp)
            .padding(start = 8.dp + step * row.indent, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tree) {
            Box(
                Modifier.size(32.dp).then(if (v.hasChildren) Modifier.clickable(onClick = onToggle) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (v.hasChildren) {
                    Icon(
                        if (v.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = (if (v.collapsed) "Expand " else "Collapse ") + row.displayName,
                        tint = lc.mutedInk,
                    )
                }
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (pinned) Box(Modifier.padding(top = 3.dp)) { PinMark() }
                Text(
                    row.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (topLevel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (row.isUnbudgeted) lc.mutedInk else lc.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                AmountLines(cell.actual, styles, strong = topLevel)
            }
            val goal = cell.goal
            if (goal != null) {
                val pct = Budgets.percent(cell, styles)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (pct != null) GoalBar(pct, overIsBad, Modifier.weight(1f).height(6.dp))
                    else Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        goalLabel(pct, goal.let { styles.format(it) }),
                        style = NumberStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                        color = lc.mutedInk,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun goalLabel(pct: Int?, goal: String) = if (pct != null) "$pct% of $goal" else "goal $goal"

/** Progress toward a goal. Past 100% the bar fills completely, in red ink for expenses. */
@Composable
private fun GoalBar(pct: Int, overIsBad: Boolean, modifier: Modifier) {
    val lc = LocalLedgerColors.current
    val fraction = (pct.coerceIn(0, 100) / 100f)
    val color = if (pct > 100 && overIsBad) lc.redInk else lc.rule
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(lc.hairline)) {
        if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color))
    }
}

@Composable
private fun BudgetTotalRow(cell: BudgetCell, styles: Styles) {
    Column {
        TotalRow("Total", cell.actual, styles, doubleRule = true)
        val goal = cell.goal
        if (goal != null) {
            Text(
                "Goals total ${styles.format(goal)}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalLedgerColors.current.mutedInk,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- table

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BudgetTable(
    report: BudgetReport,
    journal: Journal,
    options: Options,
    rulePattern: String,
    visible: List<VisibleRow<BudgetRow>>,
    onToggleCollapsed: (String) -> Unit,
    onOpenRegister: (RegisterTarget) -> Unit,
    onLongPress: (String) -> Unit,
    pinned: List<PinnedLine>,
    pinnedAtZero: Int,
    target: (BudgetRow) -> RegisterTarget?,
) {
    // One scroll state shared by every row, so all the period columns scroll together.
    val columns = rememberScrollState()
    val styles = journal.styles
    val lc = LocalLedgerColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "notice") { BudgetNotice(report, journal, rulePattern) }
        stickyHeader(key = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .drawBehind {
                        val w = 1.5.dp.toPx()
                        drawLine(lc.rule, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                    }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    if (options.endBalance) "Balance at end" else "Account",
                    style = MaterialTheme.typography.labelLarge,
                    color = lc.mutedInk,
                    modifier = Modifier.width(NAME_WIDTH).padding(start = 16.dp),
                )
                Row(Modifier.horizontalScroll(columns)) {
                    report.periods.forEach { p ->
                        Text(
                            shortLabel(p, options.interval),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(CELL_WIDTH).padding(horizontal = 8.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        val pins = pinned.filter { it.budget != null }
        if (showPinned(pins, pinnedAtZero)) {
            item(key = "pin-header") { PinnedHeader(pinnedAtZero) }
            items(pins, key = { "pin/${it.account}" }) { p ->
                val row = p.budget!!
                val t = target(row)
                TableRow(
                    VisibleRow(row.copy(indent = 0, displayName = p.account), false, false, "pin/${p.account}"),
                    styles, columns, {}, { onLongPress(p.account) }, t?.let { { onOpenRegister(it) } },
                )
            }
            item(key = "pin-end") { PinnedEnd() }
        }
        items(visible, key = { it.key }) { v ->
            val t = target(v.row)
            TableRow(
                v, styles, columns, { onToggleCollapsed(v.key) }, { onLongPress(hideName(v.row.fullName)) },
                t?.let { { onOpenRegister(it) } },
            )
        }
        item(key = "total") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "Total",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(NAME_WIDTH).padding(start = 16.dp, top = 10.dp),
                )
                Row(Modifier.horizontalScroll(columns)) {
                    report.totals.forEach { TableCell(it, styles, strong = true) }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableRow(
    v: VisibleRow<BudgetRow>,
    styles: Styles,
    columns: ScrollState,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val lc = LocalLedgerColors.current
    val row = v.row
    val pinned = LocalTreeLook.current.isPinned(row.fullName)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClickLabel = "Pin or hide", onLongClick = onLongClick, onClick = onClick ?: {})
            .treeRow(
                indent = row.indent, step = 10.dp, guideStart = 0.dp, branch = branchColor(row.fullName),
                pinned = pinned, share = null, barStart = 0.dp, lc = lc, guides = false,
            ),
    ) {
        Row(
            Modifier
                .width(NAME_WIDTH)
                .padding(start = 8.dp + 10.dp * row.indent, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // The arrow collapses or expands; tapping elsewhere on the row opens its transactions.
            Box(
                Modifier.size(28.dp).then(if (v.hasChildren) Modifier.clickable(onClick = onToggle) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (v.hasChildren) {
                    Icon(
                        if (v.collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = (if (v.collapsed) "Expand " else "Collapse ") + row.displayName,
                        tint = lc.mutedInk,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (pinned) Box(Modifier.padding(top = 4.dp)) { PinMark(14.dp) }
            Text(
                row.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.isUnbudgeted) lc.mutedInk else lc.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.horizontalScroll(columns)) {
            row.cells.forEach { TableCell(it, styles) }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun TableCell(cell: BudgetCell, styles: Styles, strong: Boolean = false) {
    Column(
        Modifier.width(CELL_WIDTH).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AmountLines(cell.actual, styles, strong = strong)
        val goal = cell.goal
        if (goal != null) {
            Text(
                goalLabel(Budgets.percent(cell, styles), styles.format(goal)),
                style = NumberStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = LocalLedgerColors.current.mutedInk,
                textAlign = TextAlign.End,
                maxLines = 2,
            )
        }
    }
}

// ---------------------------------------------------------------------- labels

/** The stepper's label: "October 2026", "Q4 2026", or for -H "Balances on 31 Oct 2026". */
fun periodLabel(span: DateSpan, interval: ReportInterval, endBalance: Boolean): String =
    if (endBalance) "Balances on ${span.end!!.minusDays(1).format(DAY_LONG)}"
    else describeSpan(span).let { if (interval == ReportInterval.WEEKLY) "Week of ${span.start!!.format(DAY_LONG)}" else it }

/** A table column heading. */
private fun shortLabel(span: DateSpan, interval: ReportInterval): String {
    val s = span.start!!
    return when {
        interval == ReportInterval.MONTHLY && s.dayOfMonth == 1 -> s.format(MONTH_SHORT)
        interval == ReportInterval.QUARTERLY && s.dayOfMonth == 1 && (s.monthValue - 1) % 3 == 0 -> "Q${(s.monthValue - 1) / 3 + 1} ${s.year}"
        interval == ReportInterval.YEARLY && s.dayOfYear == 1 -> s.year.toString()
        else -> s.format(DAY_SHORT)
    }
}
