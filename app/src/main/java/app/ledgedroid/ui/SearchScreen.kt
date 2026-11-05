package app.ledgedroid.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.Journal
import app.ledgedroid.core.TransactionSearch

/** Search every transaction in the journal, newest first. */
@Composable
fun SearchScreen(
    journal: Journal?,
    text: String,
    results: TransactionSearch.Result?,
    onText: (String) -> Unit,
    onBack: () -> Unit,
    onOpenTransaction: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val lc = LocalLedgerColors.current

    Scaffold(topBar = { LedgerTopBar("Search transactions", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Searched when Enter (or ✓) is pressed, not while typing.
            FilterField(
                applied = text,
                onApply = onText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
                placeholder = "cafe, 4.50, tag:trip, amt:>100, date:2026-10",
                message = results?.error,
                isError = results?.error != null,
            )
            when {
                journal == null || results == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                text.isBlank() -> Text(
                    "Words match descriptions, accounts, comments and amounts. You can also use " +
                            "desc:, payee:, acct:, amt:<10, date:2026-10, tag:trip=lisbon, status:*, cur:EUR and not:.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item(key = "count") {
                        Text(
                            when {
                                results.total == 0 -> "No transactions match."
                                results.total == 1 -> "1 transaction"
                                results.total > results.transactions.size ->
                                    "${results.total} transactions; showing the newest ${results.transactions.size}"

                                else -> "${results.total} transactions"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = lc.mutedInk,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                    items(results.transactions, key = { "t:" + it.index }) { t ->
                        TransactionRow(t, journal.styles) { onOpenTransaction(t.index) }
                    }
                }
            }
        }
    }
}
