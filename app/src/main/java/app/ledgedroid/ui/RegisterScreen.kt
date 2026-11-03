package app.ledgedroid.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.Accumulation
import app.ledgedroid.core.AmountSign
import app.ledgedroid.core.RegisterFilter
import app.ledgedroid.core.RegisterRow
import app.ledgedroid.core.Status
import app.ledgedroid.core.Styles
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    ui: RegisterUi?,
    target: RegisterTarget,
    onBack: () -> Unit,
    onOpenTransaction: (Int) -> Unit,
    onSign: (AmountSign) -> Unit,
    onFilter: (RegisterFilter) -> Unit,
) {
    val lc = LocalLedgerColors.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to balances")
                    }
                },
                title = {
                    Column {
                        Text(
                            target.account.substringAfterLast(':'),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ui != null) {
                            Text(
                                periodLine(ui.span, ui.accumulation),
                                style = MaterialTheme.typography.bodySmall,
                                color = lc.mutedInk,
                                maxLines = 1,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (ui == null) {
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            RegisterList(ui, target, Modifier.padding(padding), onOpenTransaction, onSign, onFilter)
        }
    }
}

@Composable
private fun RegisterList(
    ui: RegisterUi,
    target: RegisterTarget,
    modifier: Modifier,
    onOpenTransaction: (Int) -> Unit,
    onSign: (AmountSign) -> Unit,
    onFilter: (RegisterFilter) -> Unit,
) {
    val lc = LocalLedgerColors.current
    val styles = ui.journal.styles
    val report = ui.report
    // The side chosen on the screen (it may be a moment ahead of the list while that is worked out).
    val choice = ui.signChoices.firstOrNull { it.sign == target.amountSign }
    // Only offered when the account has both kinds in the period, or one is chosen (to get back to All).
    // While advanced filters are set it stays, so it doesn't come and go with each letter typed.
    val offerSigns = ui.signChoices.size == 2 &&
            ((report.positives > 0 && report.negatives > 0) || target.amountSign != AmountSign.ALL || target.filter.isActive)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "summary") {
            Text(
                target.account + (if (target.includeSubaccounts) " and subaccounts" else "") +
                        // Says what the filter shows, and the filter term that does the same, to learn it.
                        (choice?.let { " · ${it.summary} only (${it.sign.query})" } ?: "") +
                        (if (target.filter.isActive) " · " + target.filter.describe() else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = lc.mutedInk,
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 8.dp),
            )
            if (offerSigns) SignFilter(ui, target.amountSign, onSign)
            AdvancedFilters(ui, target.filter, onFilter)
            TotalRow(
                // Named for the list shown, which the total belongs to.
                ui.signChoice?.total ?: when {
                    ui.target.filter.isActive -> "Total"
                    ui.accumulation == Accumulation.END_BALANCE -> "Balance"
                    else -> "Change"
                },
                report.total, styles, doubleRule = true,
            )
        }
        if (report.rows.isEmpty()) {
            item(key = "empty") {
                Text(
                    when {
                        ui.target.filter.isActive -> "No transactions match these filters."
                        else -> ui.signChoice?.empty ?: "No postings in this period."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = lc.mutedInk,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
        items(report.rows) { row -> RegisterItem(row, target, styles) { onOpenTransaction(row.transactionIndex) } }
        val opening = report.opening
        if (opening != null) {
            item(key = "opening") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Balance before this period",
                        style = MaterialTheme.typography.bodyLarge,
                        color = lc.mutedInk,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    AmountLines(opening, styles)
                }
            }
        }
    }
}

/**
 * All · In · Out (or Charges · Payments, Spending · Refunds... for the account's type), each side with
 * how many postings it has in the period. Tapping the chosen side again shows all of them.
 */
@Composable
private fun SignFilter(ui: RegisterUi, selected: AmountSign, onSign: (AmountSign) -> Unit) {
    val report = ui.report
    val options = listOf(Triple(AmountSign.ALL, "All", null as Int?)) + ui.signChoices.map { c ->
        Triple(c.sign, c.label, if (c.sign == AmountSign.POSITIVE) report.positives else report.negatives)
    }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { i, (sign, label, count) ->
            val isSelected = sign == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSign(if (isSelected) AmountSign.ALL else sign) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                icon = {},
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (count != null) {
                        Text(
                            " $count",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterItem(row: RegisterRow, target: RegisterTarget, styles: Styles, onClick: () -> Unit) {
    val lc = LocalLedgerColors.current
    val today = LocalDate.now()
    val inSubaccount = row.account != target.account
    val others = row.otherAccounts.joinToString(", ").ifEmpty { "no other accounts" }
    val where =
        if (inSubaccount) "In ${row.account.removePrefix(target.account + ":")}, with $others" else "With $others"
    val detail = if (row.generated) "Auto posting. $where" else where

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
            row.date.format(if (row.date.year == today.year) SHORT_DATE else LONG_DATE),
            style = NumberStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
            color = lc.mutedInk,
            modifier = Modifier.width(60.dp).padding(start = 8.dp, top = 3.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                row.description.ifEmpty { "(no description)" } + if (row.status == Status.PENDING) " (pending)" else "",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = lc.mutedInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            AmountLines(row.amount, styles)
            styles.formatLines(row.runningTotal).forEach {
                Text(
                    it,
                    style = NumberStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                    color = lc.mutedInk,
                    maxLines = 1,
                )
            }
        }
    }
}
