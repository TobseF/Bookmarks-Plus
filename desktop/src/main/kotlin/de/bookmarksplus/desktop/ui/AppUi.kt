package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.AppInfo
import de.bookmarksplus.data.AppPlatform
import de.bookmarksplus.data.AppSystem
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.PackageManager
import de.bookmarksplus.desktop.AppState

/** Software directories whose page can be stored for a bookmark. */
enum class Directory(val label: String, val letter: String, val color: Color) {
    Flathub("Flathub", "F", Color(0xFF4A86CF)),
    AlternativeTo("AlternativeTo", "A", Color(0xFF0B3C5F)),
}

fun Item.directoryUrl(d: Directory): String? = when (d) {
    Directory.Flathub -> app.flathubUrl
    Directory.AlternativeTo -> app.alternativetoUrl
}

/** Small icon for a directory; clickable with [onClick] and with tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryMark(d: Directory, size: Dp = 16.dp, tooltip: String? = null, onClick: (() -> Unit)? = null) {
    val mark = @Composable {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(d.color)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it },
            contentAlignment = Alignment.Center,
        ) {
            Text(d.letter, color = Color.White, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.Bold, lineHeight = (size.value * 0.7f).sp)
        }
    }
    if (tooltip == null) mark() else TooltipArea(
        tooltip = {
            Text(tooltip, fontSize = 12.sp, color = Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp))
        },
        delayMillis = 400,
    ) { mark() }
}

/** Icons for all stored directory pages; click opens the page. Empty if none is stored. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryLinks(state: AppState, item: Item, size: Dp = 14.dp, spacing: Dp = 4.dp) {
    val present = Directory.entries.mapNotNull { d -> item.directoryUrl(d)?.let { d to it } }
    val github = item.githubUrl
    if (present.isEmpty() && github == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        github?.let { g -> androidx.compose.foundation.TooltipArea(tooltip = { Text(tr("app.openOnGithub"), fontSize = 12.sp, color = Palette.Text, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp)) }, delayMillis = 400) { GithubBadge(size) { state.open(item.copy(url = g)) } } }
        present.forEach { (d, url) -> DirectoryMark(d, size, tr("app.openOn", d.label)) { state.open(item.copy(url = url)) } }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}

fun formatCount(n: Long): String = "%,d".format(n).replace(',', '.')

private val SOURCE_LABELS = mapOf(
    "user" to tr("app.source.custom"), "flathub" to "Flathub", "alternativeto" to "AlternativeTo", "sourceforge" to "SourceForge",
    "ai" to "KI", "heuristic" to tr("app.source.title"),
)

@Composable
private fun Chip(text: String, active: Boolean = true, onClick: (() -> Unit)? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        text, fontSize = 12.sp, color = if (active) Palette.Text else Palette.TextFaint,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (active) accent.copy(alpha = 0.16f) else Palette.Surface)
            .border(1.dp, if (active) accent.copy(alpha = 0.45f) else Palette.OutlineSoft, RoundedCornerShape(50))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun Row2(key: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = 12.sp, color = Palette.TextFaint, modifier = Modifier.width(110.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** "Program" section in the detail view. Empty fields are hidden. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppSection(state: AppState, item: Item) {
    val a = item.app
    val busy = item.id in state.appBusy
    Column(Modifier.fillMaxWidth()) {
        a.name?.let { n ->
            Row2(tr("app.name")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, fontSize = 13.sp, color = Palette.Text, fontWeight = FontWeight.Medium)
                    SOURCE_LABELS[a.nameSource]?.let { Spacer(Modifier.width(8.dp)); Text(it, fontSize = 11.sp, color = Palette.TextFaint) }
                }
            }
        }
        if (a.platforms.isNotEmpty()) Row2(tr("app.platform")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.platforms.forEach { p -> Chip(AppPlatform.of(p)?.label ?: p) }
            }
        }
        if (a.systems.isNotEmpty()) Row2(tr("app.system")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.systems.forEach { s -> Chip(AppSystem.of(s)?.label ?: s) }
            }
        }
        a.license?.let { l -> Row2(tr("app.license")) { Text(l, fontSize = 12.sp, color = Palette.Text) } }
        if (a.flathubUrl != null || a.flathubInstalls != null) Row2("Flathub") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                a.flathubUrl?.let { u -> DirectoryMark(Directory.Flathub, 14.dp, tr("app.openOnFlathub")) { state.open(item.copy(url = u)) }; Spacer(Modifier.width(8.dp)) }
                Text(
                    listOfNotNull(
                        a.flathubInstalls?.let { "${formatCount(it)} Downloads" },
                        a.flathubDownloadSize?.let { "${formatSize(it)} Download" },
                        a.flathubInstalledSize?.let { "${formatSize(it)} installiert" },
                    ).joinToString(" · ").ifEmpty { a.flathubId ?: "" },
                    fontSize = 12.sp, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        a.alternativetoUrl?.let { u ->
            Row2("AlternativeTo") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.open(item.copy(url = u)) }) {
                    DirectoryMark(Directory.AlternativeTo, 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("app.alternatives"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        // package IDs: only those set, click copies the install command
        a.packages.forEach { (pm, id) ->
            Row2(pm.label) {
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.copyText(pm.command(id), tr("app.commandCopied", pm.command(id))) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(id, fontSize = 12.sp, color = Palette.Text, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.ContentCopy, tr("app.copyInstall"), Modifier.size(12.dp), tint = Palette.TextFaint)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (busy) Muted(tr("app.loading"))
        else Text(
            if (a.isApp) tr("app.refresh") else tr("app.detect"),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.appsFor(item.id) }.padding(2.dp),
        )
    }
}

/**
 * App fields in edit mode: name, platform, system, license. Package IDs and directory pages are in a
 * collapsed "Advanced" area so that the form stays tidy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppEditor(app: AppInfo, onChange: (AppInfo) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val filled = app.packages.size + listOfNotNull(app.flathubUrl, app.alternativetoUrl, app.repoUrl).size
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("app.header"), style = SectionLabel, modifier = Modifier.padding(top = 6.dp))
        OutlinedTextField(app.name.orEmpty(), { onChange(app.copy(name = it)) }, label = { Text(tr("app.appName")) }, singleLine = true,
            placeholder = { Text("z. B. FileZilla") }, modifier = Modifier.fillMaxWidth())
        Text(tr("app.platform"), fontSize = 12.sp, color = Palette.TextFaint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppPlatform.entries.forEach { p ->
                val on = p.key in app.platforms
                Chip(p.label, on) { onChange(app.copy(platforms = if (on) app.platforms - p.key else app.platforms + p.key)) }
            }
        }
        Text(tr("app.system"), fontSize = 12.sp, color = Palette.TextFaint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppSystem.entries.forEach { s ->
                val on = s.key in app.systems
                Chip(s.label, on) { onChange(app.copy(systems = if (on) app.systems - s.key else app.systems + s.key)) }
            }
        }
        OutlinedTextField(app.license.orEmpty(), { onChange(app.copy(license = it)) }, label = { Text(tr("app.license")) }, singleLine = true,
            placeholder = { Text(tr("app.licenseHint")) }, modifier = Modifier.fillMaxWidth())

        // Advanced: package IDs and directory pages (collapsed)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Surface).clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = Palette.TextMuted)
            Spacer(Modifier.width(8.dp))
            Text(tr("app.advanced"), fontSize = 13.sp, color = Palette.Text, modifier = Modifier.weight(1f))
            if (filled > 0) { Spacer(Modifier.width(8.dp)); Text(tr("app.nSet", filled), fontSize = 11.sp, color = Palette.TextFaint) }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                PackageManager.entries.forEach { pm ->
                    OutlinedTextField(
                        app.pkg(pm).orEmpty(), { onChange(app.withPkg(pm, it)) }, singleLine = true,
                        label = { Text("${pm.label}-ID") },
                        placeholder = { Text(PLACEHOLDER[pm].orEmpty()) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(app.repoUrl.orEmpty(), { onChange(app.copy(repoUrl = it)) }, singleLine = true,
                    label = { Text("GitHub-URL") }, placeholder = { Text("https://github.com/owner/repo") },
                    leadingIcon = { GithubBadge(16.dp) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(app.flathubUrl.orEmpty(), { onChange(app.copy(flathubUrl = it)) }, singleLine = true,
                    label = { Text("Flathub-URL") }, placeholder = { Text("https://flathub.org/apps/org.example.App") },
                    leadingIcon = { DirectoryMark(Directory.Flathub, 16.dp) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(app.alternativetoUrl.orEmpty(), { onChange(app.copy(alternativetoUrl = it)) }, singleLine = true,
                    label = { Text("AlternativeTo-URL") }, placeholder = { Text("https://alternativeto.net/software/example/about/") },
                    leadingIcon = { DirectoryMark(Directory.AlternativeTo, 16.dp) }, modifier = Modifier.fillMaxWidth())
                Muted(tr("app.linksHint"), maxLines = 2)
            }
        }
    }
}

private val PLACEHOLDER = mapOf(
    PackageManager.Winget to "FileZilla.FileZilla", PackageManager.Chocolatey to "filezilla", PackageManager.Scoop to "extras/filezilla",
    PackageManager.Brew to "--cask filezilla", PackageManager.Npm to "@scope/paket",
)
