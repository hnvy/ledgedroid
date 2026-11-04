package app.ledgedroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.AccountListMode
import app.ledgedroid.core.Accumulation
import app.ledgedroid.core.BalanceRow
import app.ledgedroid.core.withoutEmptySubtrees
import app.ledgedroid.core.CompoundSection
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.Journal
import app.ledgedroid.core.MixedAmount
import app.ledgedroid.core.MultiPeriodReport
import app.ledgedroid.core.MultiPeriodRow
import app.ledgedroid.core.ReportInterval
import app.ledgedroid.core.Styles
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

private val NAME_COLUMN = 148.dp
private val PERIOD_COLUMN = 112.dp
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")
private val WEEK: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * The balance report with a column per period (hledger balance -M), plus total and average columns
 * for changes. Each row has a small bar chart of its trend under the name, so spending patterns show
 * without scrolling across.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiPeriodContent(
    report: ReportUi,
    options: Options,
    collapsed: Set<String>,
    onToggleCollapsed: (String) -> Unit,
    onOpenRegister: (RegisterTarget) -> Unit,
    canWidenPeriod: Boolean,
    onShowAllDates: () -> Unit,
    onClearFilter: () -> Unit,
    onLongPress: (String) -> Unit,
) {
    val journal = report.journal
    val styles = journal.styles
    val tree = options.mode == AccountListMode.TREE
    val columns = rememberScrollState()
    val single = report.multi
    val compound = report.multiCompound
    val periods = single?.periods ?: compound?.periods ?: emptyList()
    val historical = single?.totals?.total == null && (compound?.net?.total == null)
    val whole = if (periods.isEmpty()) DateSpan.ALL else DateSpan(periods.first().start, periods.last().end)

    // Start scrolled to the latest periods, which are usually the interesting ones.
    LaunchedEffect(periods.size, periods.firstOrNull()) { columns.scrollTo(columns.maxValue) }
    LaunchedEffect(columns.maxValue) { if (columns.value == 0) columns.scrollTo(columns.maxValue) }

    val isEmpty =
        (single != null && single.rows.isEmpty()) || (compound != null && compound.sections.all { it.report.rows.isEmpty() })
    if (periods.isEmpty() || isEmpty) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing to show", style = MaterialTheme.typography.titleMedium)
            Text(
                "No transactions match this period and filter.",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalLedgerColors.current.mutedInk,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (canWidenPeriod) {
                OutlinedButton(
                    onClick = onShowAllDates,
                    modifier = Modifier.padding(top = 16.dp)
                ) { Text("Show all dates") }
            }
            if (options.query.isNotEmpty()) {
                OutlinedButton(
                    onClick = onClearFilter,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Clear filter") }
            }
        }
        return
    }

    if (!options.periodTable) {
        OnePeriodAtATime(report, periods, options, collapsed, onToggleCollapsed, onOpenRegister, onLongPress)
        return
    }

    fun target(row: MultiPeriodRow, section: CompoundSection?) =
        RegisterTarget(row.fullName, includeSubaccounts = tree || row.atDepthLimit, section = section, span = whole)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        stickyHeader(key = "header") { HeaderRow(periods, options.interval, historical, columns) }
        val pins = report.pinned.filter { it.row != null }
        if (showPinned(pins, report.pinnedAtZero)) {
            item(key = "pin-header") { PinnedHeader(report.pinnedAtZero) }
            items(pins, key = { "pin/${it.account}" }) { p ->
                AccountTableRow(
                    VisibleRow(p.row!!, false, false, "pin/${p.account}"), styles, columns, false,
                    onToggle = {},
                    onLongClick = { onLongPress(p.account) },
                    onClick = { onOpenRegister(RegisterTarget(p.account, true, p.section, whole)) },
                )
            }
            item(key = "pin-end") { PinnedEnd() }
        }
        if (single != null) {
            rows(single, "", tree, collapsed, styles, columns, onToggleCollapsed, onLongPress) { onOpenRegister(target(it, null)) }
            item(key = "total") { TotalsTableRow(single.totals, styles, columns, doubleRule = true) }
        } else if (compound != null) {
            for (s in compound.sections) {
                item(key = "h:${s.section.title}") { SectionTitle(s.section.title) }
                rows(s.report, "${s.section.title}/", tree, collapsed, styles, columns, onToggleCollapsed, onLongPress) {
                    onOpenRegister(target(it, s.section))
                }
                item(key = "t:${s.section.title}") {
                    TotalsTableRow(
                        s.report.totals.copy(displayName = "Total ${s.section.title.lowercase()}"),
                        styles,
                        columns,
                        doubleRule = false
                    )
                }
            }
            item(key = "net") { TotalsTableRow(compound.net, styles, columns, doubleRule = true) }
        }
    }
}

private fun LazyListScope.rows(
    report: MultiPeriodReport,
    prefix: String,
    tree: Boolean,
    collapsed: Set<String>,
    styles: Styles,
    columns: ScrollState,
    onToggleCollapsed: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onOpen: (MultiPeriodRow) -> Unit,
) {
    val visible = visibleRowsOf(report.rows, tree, collapsed, "multi/$prefix", { it.indent }, { it.fullName })
    items(visible, key = { it.key }) { v ->
        AccountTableRow(
            v, styles, columns, tree,
            onToggle = { onToggleCollapsed(v.key) },
            onLongClick = { onLongPress(v.row.fullName) },
            onClick = { onOpen(v.row) },
        )
    }
}

/**
 * The report one period at a time, like the single-period report: swipe or use the arrows to move between
 * periods. Accounts with nothing in the period (they and all their subaccounts) are left out unless zero
 * balances are shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnePeriodAtATime(
    report: ReportUi,
    periods: List<DateSpan>,
    options: Options,
    collapsed: Set<String>,
    onToggleCollapsed: (String) -> Unit,
    onOpenRegister: (RegisterTarget) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val styles = report.journal.styles
    val tree = options.mode == AccountListMode.TREE
    val historical = report.accumulation == Accumulation.END_BALANCE
    val pager = rememberPeriodPagerState(periods)

    /** The rows' amounts in period [i], as single-period rows. */
    fun rowsIn(r: MultiPeriodReport, i: Int): List<BalanceRow> {
        val rows = r.rows.map { BalanceRow(it.fullName, it.displayName, it.indent, it.cells[i], it.atDepthLimit) }
        return if (options.showEmpty) rows else withoutEmptySubtrees(rows, { it.indent }, { it.amount.looksZero(styles) })
    }

    fun target(row: BalanceRow, section: CompoundSection?, span: DateSpan) =
        RegisterTarget(row.fullName, includeSubaccounts = tree || row.atDepthLimit, section = section, span = span)

    Column(Modifier.fillMaxSize()) {
        PagerStepper(pager) { i -> periodLabel(periods[i], options.interval, historical) }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.Top,
            key = { it },
        ) { i ->
            val period = periods[i]
            val single = report.multi
            val compound = report.multiCompound
            // This period's rows, and their amount bars (each section compared among itself).
            val singleRows = remember(single, i, options.showEmpty) { single?.let { rowsIn(it, i) }.orEmpty() }
            val sectionRows = remember(compound, i, options.showEmpty) {
                compound?.sections.orEmpty().associate { it.section.title to rowsIn(it.report, i) }
            }
            val shares = rememberShares(singleRows, tree, "multi/") +
                    sectionRows.map { (title, rows) -> rememberShares(rows, tree, "multi/$title/") }
                        .fold(emptyMap<String, Float>()) { a, b -> a + b }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                pinnedSection(
                    report.pinned, report.pinnedAtZero, styles, "", { p -> p.row?.cells?.getOrNull(i) ?: MixedAmount.ZERO },
                    onOpen = { p -> onOpenRegister(RegisterTarget(p.account, true, p.section, period)) },
                    onLongPress = onLongPress,
                )
                if (single != null) {
                    val rows = singleRows
                    if (rows.isEmpty()) item(key = "none") { NothingInPeriod() }
                    items(visibleRowsOf(rows, tree, collapsed, "multi/", { it.indent }, { it.fullName }), key = { it.key }) { v ->
                        AccountRow(v, styles, tree, { onToggleCollapsed(v.key) }, { onLongPress(v.row.fullName) }, shares[v.key]) {
                            onOpenRegister(target(v.row, null, period))
                        }
                    }
                    item(key = "total") { TotalRow("Total", single.totals.cells[i], styles, doubleRule = true) }
                } else if (compound != null) {
                    for (s in compound.sections) {
                        val title = s.section.title
                        item(key = "h:$title") { SectionHeader(title) }
                        val rows = sectionRows[title].orEmpty()
                        if (rows.isEmpty()) item(key = "e:$title") { NothingInPeriod(title.lowercase()) }
                        items(
                            visibleRowsOf(rows, tree, collapsed, "multi/$title/", { it.indent }, { it.fullName }),
                            key = { it.key },
                        ) { v ->
                            AccountRow(v, styles, tree, { onToggleCollapsed(v.key) }, { onLongPress(v.row.fullName) }, shares[v.key]) {
                                onOpenRegister(target(v.row, s.section, period))
                            }
                        }
                        item(key = "t:$title") {
                            TotalRow("Total ${title.lowercase()}", s.report.totals.cells[i], styles, doubleRule = false)
                        }
                    }
                    item(key = "net") { TotalRow(compound.netLabel, compound.net.cells[i], styles, doubleRule = true) }
                }
            }
        }
    }
}

@Composable
private fun NothingInPeriod(what: String = "amounts") {
    Text(
        "No $what in this period.",
        style = MaterialTheme.typography.bodyMedium,
        color = LocalLedgerColors.current.mutedInk,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun HeaderRow(periods: List<DateSpan>, interval: ReportInterval, historical: Boolean, columns: ScrollState) {
    val lc = LocalLedgerColors.current
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
            if (historical) "Balance at end" else "Account",
            style = MaterialTheme.typography.labelLarge,
            color = lc.mutedInk,
            modifier = Modifier.width(NAME_COLUMN).padding(start = 16.dp),
        )
        Row(Modifier.horizontalScroll(columns)) {
            periods.forEach { HeaderCell(columnLabel(it, interval)) }
            if (!historical) {
                HeaderCell("Total", bold = true)
                HeaderCell("Average", bold = true)
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun HeaderCell(text: String, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (bold) FontWeight.Bold else null,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(PERIOD_COLUMN).padding(horizontal = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountTableRow(
    v: VisibleRow<MultiPeriodRow>,
    styles: Styles,
    columns: ScrollState,
    tree: Boolean,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    val row = v.row
    val topLevel = tree && row.indent == 0
    val pinned = LocalTreeLook.current.isPinned(row.fullName)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onLongClickLabel = "Pin or hide", onLongClick = onLongClick, onClick = onClick)
            .treeRow(
                indent = row.indent, step = 10.dp, guideStart = 0.dp, branch = branchColor(row.fullName),
                pinned = pinned, share = null, barStart = 0.dp, lc = lc, guides = false,
            ),
    ) {
        Column(
            Modifier.width(NAME_COLUMN)
                .padding(start = 8.dp + 10.dp * row.indent, end = 6.dp, top = 6.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
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
                    fontWeight = if (topLevel) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Sparkline(row.cells, Modifier.padding(start = 28.dp, top = 4.dp).fillMaxWidth().height(14.dp))
        }
        Row(Modifier.horizontalScroll(columns)) {
            row.cells.forEach { AmountCell(it, styles) }
            row.total?.let { AmountCell(it, styles, strong = true) }
            row.average?.let { AmountCell(it, styles, muted = true) }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun TotalsTableRow(row: MultiPeriodRow, styles: Styles, columns: ScrollState, doubleRule: Boolean) {
    val lc = LocalLedgerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = if (doubleRule) 16.dp else 8.dp)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawLine(lc.rule, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                if (doubleRule) {
                    drawLine(
                        lc.rule,
                        Offset(0f, size.height - 5.dp.toPx()),
                        Offset(size.width, size.height - 5.dp.toPx()),
                        w
                    )
                    drawLine(
                        lc.rule,
                        Offset(0f, size.height - 1.dp.toPx()),
                        Offset(size.width, size.height - 1.dp.toPx()),
                        w
                    )
                }
            }
            .padding(bottom = if (doubleRule) 8.dp else 0.dp),
    ) {
        Text(
            row.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(NAME_COLUMN).padding(start = 16.dp, top = 10.dp, end = 6.dp),
        )
        Row(Modifier.horizontalScroll(columns)) {
            row.cells.forEach { AmountCell(it, styles, strong = true) }
            row.total?.let { AmountCell(it, styles, strong = true) }
            row.average?.let { AmountCell(it, styles, strong = true, muted = true) }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun AmountCell(
    amount: MixedAmount,
    styles: Styles,
    strong: Boolean = false,
    muted: Boolean = false,
    width: Dp = PERIOD_COLUMN
) {
    Box(Modifier.width(width).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.TopEnd) {
        if (amount.looksZero(styles)) {
            Text("·", style = NumberStyle, color = LocalLedgerColors.current.guide, textAlign = TextAlign.End)
        } else if (muted) {
            Column(horizontalAlignment = Alignment.End) {
                styles.formatLines(amount).forEach {
                    Text(
                        it,
                        style = if (strong) NumberStyleStrong else NumberStyle,
                        color = LocalLedgerColors.current.mutedInk,
                        maxLines = 1
                    )
                }
            }
        } else {
            AmountLines(amount, styles, strong = strong)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    val lc = LocalLedgerColors.current
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(lc.hairline, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
            }
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * A tiny bar chart of a row's amounts per period, for its main commodity. Bars grow up from a
 * baseline for positive amounts and down for negative ones, in red ink.
 */
@Composable
private fun Sparkline(cells: List<MixedAmount>, modifier: Modifier) {
    val lc = LocalLedgerColors.current
    val commodity =
        cells.flatMap { it.parts.keys }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
    val values = cells.map { it.quantityOf(commodity) }
    val max = values.maxOfOrNull { it.abs() }?.takeIf { it.signum() > 0 } ?: return
    val anyNegative = values.any { it.signum() < 0 }
    val anyPositive = values.any { it.signum() > 0 }
    Box(
        modifier.drawBehind {
            val n = values.size
            val gap = if (n > 24) 0.5.dp.toPx() else 1.5.dp.toPx()
            val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(0.5f)
            val baseline = when {
                anyNegative && anyPositive -> size.height / 2
                anyNegative -> 0f
                else -> size.height
            }
            val room = if (anyNegative && anyPositive) size.height / 2 else size.height
            values.forEachIndexed { i, q ->
                if (q.signum() == 0) return@forEachIndexed
                val h = (q.abs().divide(max, 6, java.math.RoundingMode.HALF_EVEN).toFloat() * room).coerceAtLeast(1f)
                val x = i * (barWidth + gap)
                val top = if (q.signum() > 0) baseline - h else baseline
                drawRect(
                    color = if (q < BigDecimal.ZERO) lc.redInk.copy(alpha = 0.7f) else lc.rule.copy(alpha = 0.7f),
                    topLeft = Offset(x, top),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                )
            }

        }.fillMaxHeight()
    )
}

private fun columnLabel(span: DateSpan, interval: ReportInterval): String {
    val s = span.start!!
    return when {
        interval == ReportInterval.MONTHLY && s.dayOfMonth == 1 -> s.format(MONTH)
        interval == ReportInterval.QUARTERLY && s.dayOfMonth == 1 && (s.monthValue - 1) % 3 == 0 -> "Q${(s.monthValue - 1) / 3 + 1} ${s.year}"
        interval == ReportInterval.YEARLY && s.dayOfYear == 1 -> s.year.toString()
        else -> s.format(WEEK)
    }
}
