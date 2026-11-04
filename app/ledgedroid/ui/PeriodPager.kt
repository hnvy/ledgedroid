package app.ledgedroid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.DateSpan
import kotlinx.coroutines.launch
import java.time.LocalDate

/*
 * Reports with a column per period can be shown one period at a time: swipe left and right, or use the
 * arrows, like flicking through the pages of a ledger.
 */

/**
 * A pager over [periods], starting at the period containing today (or the last one). It starts again
 * when the periods change, eg a different interval or dates.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPeriodPagerState(periods: List<DateSpan>): PagerState {
    val today = LocalDate.now()
    val initial = periods.indexOfFirst { it.contains(today) }.let { if (it < 0) periods.lastIndex else it }
    return key(periods.size, periods.firstOrNull()?.start?.toString()) {
        rememberPagerState(initialPage = initial.coerceAtLeast(0)) { periods.size }
    }
}

/** The period being shown, with arrows to the previous and next ones; they scroll the pager. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PagerStepper(state: PagerState, label: (Int) -> String) {
    val scope = rememberCoroutineScope()
    val page = state.currentPage
    PeriodStepper(
        label = label(page),
        position = if (state.pageCount > 1) "${page + 1} of ${state.pageCount}" else null,
        canPrev = page > 0,
        canNext = page < state.pageCount - 1,
        onPrev = { scope.launch { state.animateScrollToPage(page - 1) } },
        onNext = { scope.launch { state.animateScrollToPage(page + 1) } },
    )
}

@Composable
internal fun PeriodStepper(
    label: String,
    position: String?,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = canPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (position != null) {
                Text(
                    position,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalLedgerColors.current.mutedInk,
                )
            }
        }
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period")
        }
    }
}
