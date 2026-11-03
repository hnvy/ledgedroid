package app.ledgedroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ledgedroid.core.BranchColors
import app.ledgedroid.core.BalanceRow
import app.ledgedroid.core.MixedAmount
import app.ledgedroid.core.Styles
import app.ledgedroid.core.shareOfParent
import app.ledgedroid.core.UNBUDGETED

/**
 * How the account tree looks, chosen in Options › Appearance. Kept on the device, not in saved reports.
 *  - colors: each top-level account's branch in its own colour (picked at random, well apart from the others,
 *    and changeable): a coloured edge, guide lines, and a wash that fades with depth and across the row, so
 *    the tree reads like coloured ledger tabs.
 *  - bars: a thin bar under each account showing its share of the account above it (or, at the top and in
 *    lists, of the largest), so the big items stand out at a glance.
 */
@Immutable
data class TreeStyle(
    val colors: Boolean = false,
    val bars: Boolean = false,
    /**
     * How different the random branch colours are, from 0 (subtle: neighbouring hues, soft) to 1 (stark:
     * the whole colour wheel, vivid). See [BranchColors.rangeFor].
     */
    val contrast: Float = 1f,
    /** How much darker each sub-level's shade is, from 0 (same as the top level) to 1 (much darker). */
    val shading: Float = BranchColors.DEFAULT_SHADING,
)

/** Everything rows need to decorate themselves, provided once around the report. */
@Immutable
class TreeLook(
    val style: TreeStyle = TreeStyle(),
    val pinned: Set<String> = emptySet(),
    /** Each top-level account's hue (see [BranchColors]). */
    val hues: Map<String, Float> = emptyMap(),
) {
    /** Accounts under <unbudgeted> in the budget report are coloured, pinned and hidden by their real name. */
    fun isPinned(fullName: String) = fullName.removePrefix("$UNBUDGETED:") in pinned
}

val LocalTreeLook = compositionLocalOf { TreeLook() }

/**
 * A branch's colours: its top-level account's colour at depth 0, and a darker shade of it at each level
 * below. Branches without a colour yet are grey.
 */
@Immutable
class Branch(
    private val hue: Float?,
    private val dark: Boolean,
    private val fallback: Color,
    private val contrast: Float,
    private val shading: Float,
) {
    fun at(depth: Int): Color = hue?.let { Color(BranchColors.argb(it, dark, depth, contrast, shading)) } ?: fallback

    /** The flat tint behind a row at [depth]: its shade, see-through. */
    fun tint(depth: Int): Color = at(depth).copy(alpha = if (dark) 0.18f else 0.13f)
}

/** The branch [fullName] is on, or null when colours are off. */
@Composable
internal fun branchColor(fullName: String): Branch? {
    val look = LocalTreeLook.current
    if (!look.style.colors) return null
    val lc = LocalLedgerColors.current
    return Branch(look.hues[BranchColors.topLevel(fullName)], lc.dark, lc.otherBranch, look.style.contrast, look.style.shading)
}

/** The top-level colour for [hue] in the light or dark theme, at the chosen [contrast]. */
internal fun hueColor(hue: Float, dark: Boolean, contrast: Float = 1f): Color =
    Color(BranchColors.argb(hue, dark, 0, contrast))

/**
 * Draws a row's decoration: a flat tint of the branch colour behind the row and a coloured edge down the
 * branch (both in the shade for the row's depth, darker further down), indent guides (each in its level's shade), the pin mark's edge, the amount bar and the ruling between rows.
 * [guideStart] is where the first indent guide goes and [step] the indent per level; [barStart] is where
 * the amount bar starts (the account name).
 */
internal fun Modifier.treeRow(
    indent: Int,
    step: Dp,
    guideStart: Dp,
    branch: Branch?,
    pinned: Boolean,
    share: Float?,
    barStart: Dp,
    lc: LedgerColors,
    guides: Boolean = true,
): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    if (branch != null) {
        drawRect(branch.tint(indent))
        drawRect(branch.at(indent), size = Size(4.dp.toPx(), h))
    }
    if (pinned) drawRect(lc.pin, topLeft = Offset(w - 3.dp.toPx(), 0f), size = Size(3.dp.toPx(), h))
    if (guides) {
        for (level in 0 until indent) {
            val x = (guideStart + step * level).toPx()
            val c = branch?.at(level)?.copy(alpha = 0.7f) ?: lc.guide
            drawLine(c, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
        }
    }
    if (share != null && share > 0f) {
        val x0 = barStart.toPx()
        val room = (w - x0 - 16.dp.toPx()).coerceAtLeast(0f)
        val bh = 3.dp.toPx()
        drawRoundRect(
            color = branch?.at(indent)?.copy(alpha = 0.8f) ?: lc.rule.copy(alpha = 0.55f),
            topLeft = Offset(x0, h - bh - 3.dp.toPx()),
            size = Size((room * share).coerceAtLeast(bh), bh),
            cornerRadius = CornerRadius(bh / 2, bh / 2),
        )
    }
    val y = h - 0.5.dp.toPx()
    drawLine(lc.hairline, Offset(0f, y), Offset(w, y), 1.dp.toPx())
}

/** The star in front of a pinned account's name. */
@Composable
internal fun PinMark(size: Dp = 16.dp) {
    Icon(
        Icons.Default.Star,
        contentDescription = "Pinned",
        tint = LocalLedgerColors.current.pin,
        modifier = Modifier.padding(end = 6.dp).size(size),
    )
}

/** The setting the pinned heading's menu changes: leave out pinned accounts at 0. */
@Immutable
class PinnedControls(val hideZero: Boolean = false, val onHideZero: (Boolean) -> Unit = {})

val LocalPinnedControls = compositionLocalOf { PinnedControls() }

/** Whether to show a pinned section: anything pinned to show, or left out for being at 0 (to say so). */
internal fun showPinned(pins: List<PinnedLine>, atZero: Int) = pins.isNotEmpty() || atZero > 0

/**
 * The heading over the pinned accounts at the top of a report, saying how many are left out for being
 * at 0 ([atZero]), with a menu to choose that.
 */
@Composable
internal fun PinnedHeader(atZero: Int = 0) {
    val lc = LocalLedgerColors.current
    val controls = LocalPinnedControls.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = lc.pin, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (atZero == 0) "Pinned" else "Pinned · $atZero at 0 hidden",
            style = MaterialTheme.typography.labelLarge,
            color = lc.mutedInk,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Pinned accounts options", tint = lc.mutedInk, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Hide pinned accounts at 0") },
                    trailingIcon = { Checkbox(checked = controls.hideZero, onCheckedChange = null) },
                    onClick = { controls.onHideZero(!controls.hideZero); menu = false },
                )
            }
        }
    }
}

/** Space between the pinned accounts and the report. */
@Composable
internal fun PinnedEnd() = Spacer(Modifier.fillMaxWidth().padding(bottom = 12.dp))

/** What can be done with an account, from a long press: pin or unpin it, or hide it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountActionsSheet(
    account: String,
    pinned: Boolean,
    onPin: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    /** The colour of the account's branch, when branches are coloured; null hides the item. */
    branchColor: Color? = null,
    onColor: () -> Unit = {},
    /** Hidden accounts still count in totals (set in Hidden accounts), so hiding only takes rows away. */
    hideKeepsTotals: Boolean = false,
    /** Rename the account across the journal; null hides the item. */
    onRename: (() -> Unit)? = null,
) {
    val lc = LocalLedgerColors.current
    val name = account.removePrefix("$UNBUDGETED:")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(if (pinned) "Unpin" else "Pin to the top") },
                supportingContent = {
                    Text(
                        if (pinned) "Stop showing it at the top of the report"
                        else "Show it at the top of every report, with its total, and highlight it in the tree"
                    )
                },
                leadingContent = { Icon(Icons.Default.Star, contentDescription = null, tint = lc.pin) },
                modifier = Modifier.clickable(onClick = onPin),
            )
            if (onRename != null) {
                ListItem(
                    headlineContent = { Text("Rename…") },
                    supportingContent = { Text("Rename it, and its subaccounts, everywhere in the journal; you'll see every change first") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onRename),
                )
            }
            if (branchColor != null) {
                val top = BranchColors.topLevel(account)
                ListItem(
                    headlineContent = { Text("Colour of $top") },
                    supportingContent = { Text("Change the colour of the $top branch") },
                    leadingContent = { Swatch(branchColor, 24.dp) },
                    modifier = Modifier.clickable(onClick = onColor),
                )
            }
            ListItem(
                headlineContent = { Text("Hide") },
                supportingContent = {
                    Text(
                        when {
                            hideKeepsTotals -> "Take its row (and its subaccounts') out of reports; totals still " +
                                    "include it. Unhide it with ⋮ › Hidden accounts…"
                            name == UNBUDGETED -> "Leave amounts without budget goals out of the budget report"
                            else -> "Leave it and its subaccounts out of every report and total, until you unhide it " +
                                    "(⋮ › Hidden accounts…). The journal isn't changed."
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Close, contentDescription = null, tint = lc.mutedInk) },
                modifier = Modifier.clickable(onClick = onHide),
            )
        }
    }
}

/** Amount-bar shares for [rows], keyed like their visible rows ([prefix] + full name); empty when bars are off. */
@Composable
internal fun rememberShares(rows: List<BalanceRow>, tree: Boolean, prefix: String): Map<String, Float> {
    val on = LocalTreeLook.current.style.bars
    return remember(rows, tree, prefix, on) {
        if (!on) emptyMap()
        else rows.zip(shareOfParent(rows, tree, { it.indent }, { it.amount }))
            .mapNotNull { (r, f) -> f?.let { prefix + r.fullName to it } }.toMap()
    }
}

/**
 * The pinned accounts at the top of a single-period list (or one period of a report with columns): a
 * heading, then each account by its full name with [amountOf] it. Tap opens its transactions, long-press
 * its actions (to unpin it).
 */
internal fun LazyListScope.pinnedSection(
    pins: List<PinnedLine>,
    atZero: Int,
    styles: Styles,
    keyPrefix: String,
    amountOf: (PinnedLine) -> MixedAmount,
    onOpen: (PinnedLine) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (!showPinned(pins, atZero)) return
    item(key = "${keyPrefix}pin-header") { PinnedHeader(atZero) }
    items(pins, key = { "${keyPrefix}pin/${it.account}" }) { p ->
        val row = BalanceRow(p.account, p.account, 0, amountOf(p))
        AccountRow(VisibleRow(row, false, false, "${keyPrefix}pin/${p.account}"), styles, false, {}, { onLongPress(p.account) }) {
            onOpen(p)
        }
    }
    item(key = "${keyPrefix}pin-end") { PinnedEnd() }
}

/** A round colour sample. */
@Composable
internal fun Swatch(color: Color, size: Dp, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/**
 * Choose the colour of one branch: drag along the colour wheel, tap a preset, or roll a random one that's
 * well apart from the other branches' colours ([others]).
 */
@Composable
internal fun HuePickerDialog(
    name: String,
    initial: Float,
    others: Collection<Float>,
    contrast: Float,
    shading: Float,
    onDismiss: () -> Unit,
    onPick: (Float) -> Unit,
) {
    val lc = LocalLedgerColors.current
    var hue by remember { mutableFloatStateOf(initial) }
    // The whole wheel, at the same lightness as the branch colours, behind the slider.
    val wheel = remember(lc.dark, contrast) { (0..12).map { hueColor(it * 30f, lc.dark, contrast) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour of $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A preview like the top of the branch in the tree, with the darker shades below it.
                Column {
                    listOf(name, "sub-account", "sub-sub-account").forEachIndexed { depth, label ->
                        val shade = Color(BranchColors.argb(hue, lc.dark, depth, contrast, shading))
                        val tint = shade.copy(alpha = if (lc.dark) 0.18f else 0.13f)
                        Text(
                            label,
                            style = if (depth == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            color = if (depth == 0) lc.ink else lc.mutedInk,
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    drawRect(tint)
                                    drawRect(shade, size = Size(5.dp.toPx(), size.height))
                                }
                                .padding(start = 16.dp + 14.dp * depth, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(wheel)),
                )
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                listOf(0, 6).forEach { start ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (k in start until start + 6) {
                            val h = k * 30f
                            Swatch(hueColor(h, lc.dark, contrast), 32.dp, selected = BranchColors.distance(h, hue) < 1f) { hue = h }
                        }
                    }
                }
                val closest = others.minOfOrNull { BranchColors.distance(it, hue) }
                if (closest != null && closest < 20f) {
                    Text(
                        "Close to another branch's colour",
                        style = MaterialTheme.typography.bodySmall,
                        color = lc.mutedInk,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hue) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { hue = BranchColors.pickHue(others) }) { Text("Random") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Every top-level account with its colour, for Settings; tap one to change it. */
@Composable
internal fun BranchColorsList(names: List<String>, hues: Map<String, Float>, contrast: Float, onEdit: (String) -> Unit) {
    val lc = LocalLedgerColors.current
    names.forEach { n ->
        Row(
            Modifier.fillMaxWidth().clickable { onEdit(n) }.padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(hues[n]?.let { hueColor(it, lc.dark, contrast) } ?: lc.otherBranch, 24.dp)
            Spacer(Modifier.width(16.dp))
            Text(n, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
