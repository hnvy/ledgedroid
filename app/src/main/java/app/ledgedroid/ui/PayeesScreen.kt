package app.ledgedroid.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.DateSpan
import app.ledgedroid.core.Journal
import app.ledgedroid.core.MixedAmount
import app.ledgedroid.core.PayeeSummary
import app.ledgedroid.core.Payees
import app.ledgedroid.core.Styles
import app.ledgedroid.core.Transaction
import app.ledgedroid.core.describeSpan
import app.ledgedroid.core.transactionAmount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

enum class PayeeSort(val label: String) { RECENT("Recent"), SPENT("Spent"), COUNT("Most used"), NAME("A–Z") }

/** A list of payees, and what it was worked out for. */
class PayeesResult(val key: PayeesKey, val view: PayeesView, val payees: List<PayeeSummary>)

/**
 * What a payee list was worked out for. The journal is compared by identity: comparing two journals with
 * == would compare every transaction.
 */
class PayeesKey(val journal: Journal?, val span: DateSpan, val view: PayeesView) {
    fun same(other: PayeesKey?) = other != null && other.journal === journal && other.span == span && other.view == view
}

/**
 * Where the Payees screen was, kept while a payee is open so coming back finds it as it was: the sort, the
 * filter, the place in the list, and the list itself, so it needn't be worked out again.
 */
data class PayeesPlace(
    val sort: PayeeSort = PayeeSort.RECENT,
    val filter: String = "",
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val result: PayeesResult? = null,
)

/** Which payees the Payees screen lists. */
enum class PayeesView(val label: String) {
    DECLARED("Declared"),
    ALL("All"),
    UNDECLARED("Not declared"),
}

/**
 * Everyone the journal has transactions with, in the report's period: the payees declared with payee
 * directives (the default, and the least to add up), all of them with the undeclared ones marked, or only
 * the undeclared ones (with their payee directives to copy into the journal). A journal that declares no
 * payees always shows all of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayeesScreen(
    journal: Journal?,
    span: DateSpan,
    view: PayeesView,
    onView: (PayeesView) -> Unit,
    onBack: () -> Unit,
    onOpenPayee: (String) -> Unit,
    /** Where the screen was (see [PayeesPlace]), and how to keep it up to date. */
    place: PayeesPlace = PayeesPlace(),
    onPlace: (PayeesPlace) -> Unit = {},
) {
    val filter = place.filter
    val sort = place.sort
    // The list's place, as it was when a payee was opened; saved again on leaving, with the sort and filter
    // as they are then (not as they were when the screen opened).
    val listState = rememberLazyListState(place.scrollIndex, place.scrollOffset)
    val latestPlace: PayeesPlace by rememberUpdatedState(place)
    val latestOnPlace: (PayeesPlace) -> Unit by rememberUpdatedState(onPlace)
    DisposableEffect(Unit) {
        onDispose {
            latestOnPlace(latestPlace.copy(scrollIndex = listState.firstVisibleItemIndex, scrollOffset = listState.firstVisibleItemScrollOffset))
        }
    }
    val anyDeclared = journal?.declaredPayees?.isNotEmpty() == true
    // Without payee directives, "declared" would be nobody and "not declared" everybody.
    val shownView = if (anyDeclared) view else PayeesView.ALL
    // The list stays up while another view is worked out, so switching doesn't flash a spinner.
    val key = PayeesKey(journal, span, shownView)
    // Coming back: the list worked out before, if it's for the same journal, period and view.
    var result: PayeesResult? by remember { mutableStateOf(place.result) }
    LaunchedEffect(journal, span, shownView) {
        if (key.same(result?.key)) return@LaunchedEffect
        val list = withContext(Dispatchers.Default) {
            journal?.let {
                Payees.summaries(
                    it, span,
                    declaredOnly = shownView == PayeesView.DECLARED,
                    undeclaredOnly = shownView == PayeesView.UNDECLARED,
                )
            }
        }
        result = list?.let { PayeesResult(key, shownView, it) }
        latestOnPlace(latestPlace.copy(result = result))
    }
    val clipboard = LocalClipboardManager.current
    // "Copied" shows on the button for a moment after copying.
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Scaffold(topBar = { LedgerTopBar("Payees", onBack, subtitle = describeSpan(span)) }) { padding ->
        val all = result?.payees
        if (journal == null || all == null) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        val working = !key.same(result?.key)
        // What the list on screen is (a moment behind the buttons while the next one is worked out).
        val listView = result?.view ?: shownView
        val styles = journal.styles
        // In All, mark the payees that aren't declared.
        val markUndeclared = listView == PayeesView.ALL && anyDeclared
        val undeclared = if (listView == PayeesView.ALL) all.count { !it.declared } else 0
        val shown = all
            .filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
            .let { list ->
                when (sort) {
                    PayeeSort.RECENT -> list
                    PayeeSort.SPENT -> list.sortedByDescending { it.spent.sortKey() }
                    PayeeSort.COUNT -> list.sortedByDescending { it.transactions }
                    PayeeSort.NAME -> list.sortedBy { it.name.lowercase() }
                }
            }
        Column(Modifier.padding(padding).fillMaxSize()) {
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "controls") {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (anyDeclared) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            PayeesView.entries.forEachIndexed { i, v ->
                                SegmentedButton(
                                    selected = view == v,
                                    onClick = { onView(v) },
                                    shape = SegmentedButtonDefaults.itemShape(i, PayeesView.entries.size),
                                    icon = {},
                                ) { Text(v.label, maxLines = 1) }
                            }
                        }
                    }
                    // The list is narrowed when Enter (or ✓) is pressed, not while typing.
                    FilterField(
                        applied = filter,
                        onApply = { onPlace(place.copy(filter = it, result = result)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Find a payee",
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        PayeeSort.entries.forEachIndexed { i, s ->
                            SegmentedButton(
                                selected = sort == s,
                                onClick = { onPlace(place.copy(sort = s, result = result)) },
                                shape = SegmentedButtonDefaults.itemShape(i, PayeeSort.entries.size),
                                icon = {},
                            ) { Text(s.label, maxLines = 1) }
                        }
                    }
                    val transactions = all.sumOf { it.transactions }
                    val canCopy = listView == PayeesView.UNDECLARED && anyDeclared && all.any { it.name.isNotBlank() }
                    // The count, with Copy as payee directives beside it when listing the undeclared ones.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !anyDeclared -> "${all.size} payees, $transactions transactions. This journal declares " +
                                    "no payees (payee directives), so all of them are shown."
                            listView == PayeesView.DECLARED -> "${all.size} declared payees, $transactions transactions"
                            listView == PayeesView.UNDECLARED -> "${all.size} payees not declared, $transactions transactions"
                            else -> "${all.size} payees, $transactions transactions, $undeclared not declared"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalLedgerColors.current.mutedInk,
                        modifier = Modifier.weight(1f),
                    )
                    if (canCopy) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(Payees.directives(all.map { it.name })))
                            copied = true
                        }) { Text(if (copied) "Copied" else "Copy as payee directives") }
                    }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        when {
                            all.isNotEmpty() -> "No payees match."
                            listView == PayeesView.DECLARED -> "No declared payees have transactions in this period."
                            listView == PayeesView.UNDECLARED -> "Every payee in this period is declared."
                            else -> "No transactions in this period."
                        },
                        color = LocalLedgerColors.current.mutedInk,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items(shown, key = { "p:" + it.name }) { p ->
                PayeeRow(p, styles, markUndeclared = markUndeclared) { onOpenPayee(p.name) }
            }
        }
        }
    }
}

@Composable
private fun PayeeRow(p: PayeeSummary, styles: Styles, markUndeclared: Boolean, onClick: () -> Unit) {
    val lc = LocalLedgerColors.current
    val amount = if (!p.spent.isZero || p.received.isZero) p.spent else p.received
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(lc.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                p.name.ifEmpty { "(no payee)" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                (if (p.transactions == 1) "1 transaction" else "${p.transactions} transactions") + ", last ${shortDate(p.lastDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = lc.mutedInk,
            )
            if (markUndeclared && !p.declared) {
                Text(
                    "Not declared",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!amount.isZero) AmountLines(amount, styles)
            Text(
                when {
                    !p.spent.isZero -> "spent"
                    !p.received.isZero -> "received"
                    else -> "transfers"
                },
                style = MaterialTheme.typography.bodySmall,
                color = lc.mutedInk,
            )
        }
    }
}

/** One payee's transactions, newest first. */
@Composable
fun PayeeScreen(
    journal: Journal?,
    payee: String,
    span: DateSpan,
    onBack: () -> Unit,
    onOpenTransaction: (Int) -> Unit,
    onRepeat: (Transaction) -> Unit,
    /** Move this payee's postings from one account to another across the journal. */
    onRecategorise: () -> Unit = {},
) {
    var txns by remember(journal, payee, span) { mutableStateOf<List<Transaction>?>(null) }
    LaunchedEffect(journal, payee, span) {
        txns = withContext(Dispatchers.Default) { journal?.let { Payees.transactions(it, payee, span) } }
    }
    Scaffold(topBar = {
        LedgerTopBar(
            payee.ifEmpty { "(no payee)" },
            onBack,
            subtitle = describeSpan(span),
            actions = { if (payee.isNotEmpty()) TextButton(onClick = onRecategorise) { Text("Recategorise") } },
        )
    }) { padding ->
        val list = txns
        if (journal == null || list == null) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        val styles = journal.styles
        val summary = remember(list) {
            Payees.summaries(journal.copy(transactions = list.asReversed()), DateSpan.ALL).firstOrNull()
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "summary") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    if (summary != null && !summary.spent.isZero) TotalRow(
                        "Spent",
                        summary.spent,
                        styles,
                        doubleRule = false
                    )
                    if (summary != null && !summary.received.isZero) TotalRow(
                        "Received",
                        summary.received,
                        styles,
                        doubleRule = false
                    )
                }
                list.firstOrNull()?.let { last ->
                    OutlinedButton(
                        onClick = { onRepeat(last) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text("Add again today") }
                }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No transactions in this period.",
                        color = LocalLedgerColors.current.mutedInk,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
            items(list, key = { "t:" + it.index }) { t -> TransactionRow(t, styles) { onOpenTransaction(t.index) } }
        }
    }
}

/** A transaction in a list: date, description, accounts and the amount it moved. */
@Composable
fun TransactionRow(t: Transaction, styles: Styles, onClick: () -> Unit) {
    val lc = LocalLedgerColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(lc.hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            shortDate(t.date),
            style = NumberStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
            color = lc.mutedInk,
            modifier = Modifier.width(68.dp).padding(start = 8.dp, top = 3.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                t.description.ifEmpty { "(no description)" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                t.postings.map { it.account }.distinct().joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = lc.mutedInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        val moved: MixedAmount = transactionAmount(t)
        AmountLines(moved, styles)
    }
}
