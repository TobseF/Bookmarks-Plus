package de.bookmarksplus.android.ui

import de.bookmarksplus.android.R
import de.bookmarksplus.android.str
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.bookmarksplus.android.BookmarkStore
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import de.bookmarksplus.data.LinkHealth

/** Detail view as a bottom sheet: preview, texts, tags, folders, project data and open/share. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailSheet(store: BookmarkStore, lib: Library, item: Item, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false), containerColor = Palette.Surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            val img = rememberPreview(item)
            if (img != null) {
                Image(
                    img, null,
                    Modifier.fillMaxWidth().aspectRatio((img.width.toFloat() / img.height).coerceIn(1f, 2.2f)).clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.size(16.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemIcon(store, item, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.displayTitle, style = MaterialTheme.typography.titleLarge)
                    item.displayDomain?.let { Muted(it.removePrefix("www.")) }
                }
            }
            Spacer(Modifier.size(8.dp))
            SelectionContainer { Text(item.url, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 3) }
            Spacer(Modifier.size(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { openUrl(context, item.url) }, Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(str(R.string.open_in_browser))
                }
                FilledTonalIconButton(onClick = { shareUrl(context, item.displayTitle, item.url) }) { Icon(Icons.Default.Share, str(R.string.common_share)) }
                FilledTonalIconButton(onClick = { copyText(context, item.displayTitle, item.url) }) { Icon(Icons.Default.ContentCopy, str(R.string.copy_link)) }
            }

            // project: official website and repo side by side
            val site = item.siteUrl?.takeIf { it != item.url }
            val repo = item.githubUrl?.takeIf { it != item.url } ?: item.repoUrl?.takeIf { it != item.url }
            if (site != null || repo != null || item.downloadUrl != null) {
                Spacer(Modifier.size(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    site?.let { AssistChip(onClick = { openUrl(context, it) }, label = { Text(str(R.string.detail_website)) }, leadingIcon = { Icon(Icons.Default.Language, null, Modifier.size(18.dp)) }) }
                    repo?.let { AssistChip(onClick = { openUrl(context, it) }, label = { Text("Repository") }, leadingIcon = { Icon(Icons.Default.Code, null, Modifier.size(18.dp)) }) }
                    item.downloadUrl?.let { AssistChip(onClick = { openUrl(context, it) }, label = { Text(item.downloadLabel ?: "Download") }) }
                }
            }

            // status of the link
            when (item.health) {
                LinkHealth.Dead, LinkHealth.Unreachable -> {
                    Spacer(Modifier.size(12.dp))
                    Text(str(R.string.detail_link_health, item.health.label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    AssistChip(onClick = { openUrl(context, "https://web.archive.org/web/*/" + item.url) }, label = { Text(str(R.string.detail_search_archive)) })
                }
                LinkHealth.Redirect -> item.finalUrl?.let {
                    Spacer(Modifier.size(12.dp))
                    Muted(str(R.string.detail_redirects_to, it), maxLines = 2)
                }
                else -> {}
            }

            // GitHub/project data
            val facts = buildList {
                item.stars?.takeIf { it > 0 }?.let { add("★ ${formatStars(it)}") }
                formatDate(item.lastCommit)?.let { add(str(R.string.detail_last_commit, it)) }
                item.lastRelease?.let { add(str(R.string.detail_release, it) + (formatDate(item.lastReleaseDate)?.let { d -> " ($d)" } ?: "")) }
                (item.org ?: item.author)?.let { add(it) }
                item.app.name?.takeIf { item.app.isApp }?.let { add(str(R.string.detail_program, it)) }
                item.app.systems.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(", ")) }
                item.app.license?.let { add(it) }
            }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.size(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    facts.forEach { Fact(it, star = it.startsWith("★")) }
                }
            }

            item.anyDescription?.let { Section(str(R.string.detail_description)) { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } } }
            item.summary?.takeIf { it.isNotBlank() && it != item.anyDescription }?.let {
                Section(str(R.string.detail_summary)) { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            }

            val tags = lib.tagsOf[item.id].orEmpty().mapNotNull { lib.tagsById[it] }.sortedBy { it.label.lowercase() }
            if (tags.isNotEmpty()) Section("Tags") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { TagChip(it.label, Color(it.color)) }
                }
            }

            val folders = lib.placementsByItem[item.id].orEmpty().sortedByDescending { it.primary }.map { lib.pathString(it.folderId).ifBlank { str(R.string.nav_bookmarks) } }
            if (folders.isNotEmpty()) Section(if (folders.size > 1) str(R.string.detail_folders_count, folders.size) else str(R.string.detail_folder)) {
                folders.forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Icon(Icons.Default.Folder, null, Modifier.size(16.dp), tint = Palette.TextMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(path, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                }
            }

            val dates = listOfNotNull(
                formatDate(item.addDate)?.let { str(R.string.detail_added, it) },
                formatDate(item.lastModified)?.takeIf { item.lastModified != item.addDate }?.let { str(R.string.detail_modified, it) },
            )
            if (dates.isNotEmpty()) { Spacer(Modifier.size(16.dp)); Muted(dates.joinToString(" · "), maxLines = 2) }
            if (item.scrapedAt == null) {
                Spacer(Modifier.size(4.dp))
                Muted(str(R.string.detail_not_enriched), maxLines = 2)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.size(20.dp))
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.size(6.dp))
    content()
}

@Composable
private fun Fact(text: String, star: Boolean = false) {
    Surface(color = Palette.SurfaceHigh, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (star) {
                Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = Palette.Star)
                Spacer(Modifier.width(4.dp))
                Text(text.removePrefix("★ "), style = MaterialTheme.typography.bodySmall)
            } else Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TagChip(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = MaterialTheme.shapes.small) {
        Row(Modifier.heightIn(min = 26.dp).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}
