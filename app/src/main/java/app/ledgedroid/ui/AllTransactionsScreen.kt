package app.ledgedroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.Journal
import app.ledgedroid.core.TransactionOrder

/**
 * All transactions: every transaction in the journal, newest first (or oldest, or as written), with the
 * search language to filter them, loaded a page at a time: as many at a time as chosen, and Load more for
 * the next. Each page carries on where the last stopped, so nothing is gone through twice, and only what
 * has been loaded is kept.
 */
@Composable
fun AllTransactionsScreen(
    journal: Journal?,
    ui: LedgerViewModel.AllTransactionsUi,
    onFilter: (String) -> Unit,
    onOrder: (TransactionOrder) -> Unit,
    onPageSize: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: (Int) -> Unit,
    onScroll: (Int) -> Unit,
    onOpenTransaction: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val lc = LocalLedgerColors.current
    // Where the list was, after opening a transaction and coming back. The first item is the controls.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ui.scroll)
    DisposableEffect(Unit) { onDispose { onScroll(list.firstVisibleItemIndex) } }
    // The journal changed (an edit, a reload): load again, as many as were loaded, so nothing shown is stale.
    LaunchedEffect(journal) {
        if (journal != null && ui.journal != null && journal !== ui.journal) onRefresh(ui.items.size)
    }

    Scaffold(topBar = { LedgerTopBar("All transactions", onBack, subtitle = if (ui.total > 0) "${ui.total} in the journal" else null) }) { padding ->
        val styles = (journal ?: ui.journal)?.styles
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "controls") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Filtered when Enter (or ✓) is pressed, not while typing.
                        FilterField(
                            applied = ui.filter,
                            onApply = onFilter,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = "Filter: words, acct:, payee:, amt:>50, date:2026-10, tag:…",
                            message = ui.error,
                            isError = ui.error != null,
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            TransactionOrder.entries.forEachIndexed { i, o ->
                                SegmentedButton(
                                    selected = ui.order == o,
                                    onClick = { onOrder(o) },
                                    shape = SegmentedButtonDefaults.itemShape(i, TransactionOrder.entries.size),
                                    icon = {},
                                ) { Text(o.label, maxLines = 1) }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("At a time:", style = MaterialTheme.typography.labelLarge, color = lc.mutedInk)
                            LedgerViewModel.TX_PAGE_SIZES.forEach { n ->
                                FilterChip(selected = ui.pageSize == n, onClick = { onPageSize(n) }, label = { Text("$n") })
                            }
                        }
                        Text(
                            when {
                                ui.error != null -> ""
                                ui.filter.isBlank() -> "Showing ${ui.items.size} of ${ui.total}"
                                ui.items.isEmpty() && !ui.loading -> "No transactions match."
                                ui.next != null -> "${ui.items.size} shown · more below"
                                else -> "${ui.items.size} match"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = lc.mutedInk,
                        )
                    }
                }
                if (styles != null) {
                    items(ui.items, key = { "t:" + it.index }) { t -> TransactionRow(t, styles) { onOpenTransaction(t.index) } }
                }
                if (ui.next != null) {
                    item(key = "more") {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            FilledTonalButton(onClick = onLoadMore, enabled = !ui.loading) {
                                if (ui.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("Load ${ui.pageSize} more")
                            }
                        }
                    }
                }
            }
        }
    }
}
