package app.ledgedroid.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.ledgedroid.R
import app.ledgedroid.core.About
import app.ledgedroid.core.AboutBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the About screen shows: its text, and the build's details (version, commit, date, repository). */
private class AboutContent(val text: String, val values: Map<String, String>)

/**
 * Settings › About: the app's icon, name and version, the About text (set by the release workflow, or the
 * one in the repository), and the build's details when the workflow made it.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val content: AboutContent? by produceState<AboutContent?>(null) {
        value = withContext(Dispatchers.IO) { readAbout(context) }
    }
    val lc = LocalLedgerColors.current
    Scaffold(topBar = { LedgerTopBar("About", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The launcher icon's coin on its background.
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF222034)), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Ledgedroid", style = MaterialTheme.typography.titleLarge)
                    content?.values?.get("version")?.let { v ->
                        Text("Version $v", style = MaterialTheme.typography.bodyMedium, color = lc.mutedInk)
                    }
                }
            }
            HorizontalDivider(color = lc.hairline)
            val c = content
            if (c != null) {
                About.blocks(c.text, c.values).forEach { b ->
                    when (b) {
                        is AboutBlock.Heading -> Text(
                            linked(b.text),
                            style = if (b.level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        is AboutBlock.Paragraph -> Text(linked(b.text), style = MaterialTheme.typography.bodyMedium)
                        is AboutBlock.Bullet -> Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                            Text(linked(b.text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // The build's details, when the release workflow made it.
                val built = listOfNotNull(
                    c.values["built"]?.let { "Built $it" },
                    c.values["commit"]?.let { "from $it" },
                    c.values["versionCode"]?.let { "version code $it" },
                )
                if (c.values.containsKey("commit") || c.values.containsKey("built")) {
                    HorizontalDivider(color = lc.hairline, modifier = Modifier.padding(top = 8.dp))
                    Text(built.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = lc.mutedInk)
                }
            }
        }
    }
}

/** [text] with its links clickable (they open in the browser). */
@Composable
private fun linked(text: String): AnnotatedString {
    val links = About.links(text)
    if (links.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (r in links) {
            append(text.substring(at, r.first))
            val url = text.substring(r)
            withLink(LinkAnnotation.Url(url, style)) { append(url) }
            at = r.last + 1
        }
        append(text.substring(at))
    }
}

/** The About text and the build's details, from the app's assets (written by the release workflow). */
private fun readAbout(context: Context): AboutContent {
    fun asset(name: String): String? = runCatching { context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    val values = HashMap<String, String>()
    asset("build.properties")?.let { values.putAll(About.properties(it)) }
    // The version from the app itself, so it's right even for builds the workflow didn't make.
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName?.let { values["version"] = it }
        @Suppress("DEPRECATION")
        val code: Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        values["versionCode"] = code.toString()
    }
    return AboutContent(asset("about.md") ?: "", values)
}
