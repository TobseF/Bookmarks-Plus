package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Shots
import de.bookmarksplus.desktop.AppState
import de.bookmarksplus.desktop.ImageInput
import de.bookmarksplus.desktop.loadThumbSource
import de.bookmarksplus.desktop.setCustomThumb
import de.bookmarksplus.desktop.setThumbChoice

private data class Candidate(val key: String, val path: String, val label: String, val sub: String)

private val FlathubBlue = Color(0xFF4A90D9)
private val AlternativeToOrange = Color(0xFF0B3C5F)
private val GithubGreen = Color(0xFF3FB950)

/**
 * Selection of the preview image (gallery/tiles): website, GitHub README card or share image, screenshots or an
 * own image. Sources still missing (Flathub, AlternativeTo, README) appear as a blurred tile with the
 * source icon and can be reloaded specifically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThumbnailDialog(state: AppState, item: Item) {
    val close = { state.thumbPicker = null }
    val github = item.domain == "github.com"
    val auto = imageCandidates(item.copy(thumbChoice = null)).firstOrNull()
    val current = item.chosenThumbPath ?: auto

    val candidates = buildList {
        item.thumbnailPath?.let { add(Candidate("website", it, tr("detail.website"), tr("thumbDlg.pageScreenshot"))) }
        item.cardImagePath?.let { add(Candidate("card", it, if (github) "GitHub README" else tr("detail.shareImage"), if (github) tr("thumbDlg.generated") else "og:image")) }
        item.customThumbPath?.let { add(Candidate("custom", it, tr("thumbDlg.customImage"), tr("thumbDlg.uploadClipboard"))) }
        item.shots.filter { !it.hidden }.forEach { add(Candidate("shot:${it.hash}", it.path, "Screenshot", Shots.label(it.source))) }
    }

    // sources that can be reloaded specifically as long as they have not delivered anything yet
    val appLike = item.app.name != null || item.isRepo || item.app.flathubUrl != null || item.app.alternativetoUrl != null
    val missing = buildList {
        if (appLike && item.shots.none { it.source == Shots.FLATHUB }) add("flathub")
        if (appLike && item.shots.none { it.source == Shots.ALTERNATIVETO }) add("alternativeto")
        if (github && item.hasReadme && item.cardImagePath == null) add("readme")
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 1040.dp).fillMaxWidth(0.94f).heightIn(max = 820.dp)
                .clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh)
                .border(1.dp, Palette.OutlineSoft, RoundedCornerShape(16.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("thumbDlg.title"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text(
                    item.app.name?.takeIf { it.isNotBlank() } ?: item.displayTitle, fontSize = 13.sp, color = Palette.TextMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = close) { Icon(Icons.Filled.Close, tr("common.close"), tint = Palette.Text) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp)) {
                if (candidates.isEmpty() && missing.isEmpty()) {
                    Text(tr("thumbDlg.empty"), color = Palette.TextMuted, fontSize = 13.sp)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    candidates.forEach { c ->
                        ImageTile(state, item, c, selected = c.path == current) {
                            state.setThumbChoice(item.id, c.key)
                            close()
                        }
                    }
                    missing.forEach { src ->
                        val (label, brand, letter, iconHash) = when (src) {
                            "flathub" -> Quad("Flathub", FlathubBlue, "F", item.flathubIconHash)
                            "alternativeto" -> Quad("AlternativeTo", AlternativeToOrange, "A", item.alternativetoIconHash)
                            else -> Quad("GitHub README", GithubGreen, "G", item.avatarHash)
                        }
                        SourceTile(
                            state, item, current, label, if (src == "readme") tr("thumbDlg.readmeCard") else tr("thumbDlg.loadImages"),
                            brand, letter, iconHash, loading = src in state.thumbLoading,
                        ) { state.loadThumbSource(item.id, src) }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        ImageInput.pickFile(tr("thumbDlg.chooseCustom"))?.let { f ->
                            if (f.length() > ImageInput.MAX_FILE_BYTES) state.notify(tr("detail.fileTooLarge"))
                            else { state.setCustomThumb(item.id, ImageInput.readFile(f)); close() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Icon(Icons.Filled.UploadFile, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("thumbDlg.chooseFile"))
                }
                OutlinedButton(onClick = {
                    val bytes = ImageInput.fromClipboard()
                    if (bytes == null) state.notify(tr("thumbDlg.noClipImage"))
                    else { state.setCustomThumb(item.id, bytes); close() }
                }) {
                    Icon(Icons.Filled.ContentPaste, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("thumbDlg.fromClipboard"))
                }
                Spacer(Modifier.weight(1f))
                if (item.thumbChoice != null) OutlinedButton(onClick = { state.setThumbChoice(item.id, null); close() }) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(tr("thumbDlg.auto"))
                }
            }
        }
    }
}

private data class Quad(val label: String, val brand: Color, val letter: String, val iconHash: String?)

@Composable
private fun ImageTile(state: AppState, item: Item, c: Candidate, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bmp = rememberItemImage(state, item, c.path)
    Box(
        Modifier.width(216.dp).aspectRatio(4f / 3f).clip(shape).background(Palette.Surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Palette.OutlineSoft, shape)
            .clickable(onClick = onClick),
    ) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        TileLabel(c.label, c.sub, Modifier.align(Alignment.BottomStart))
        if (selected) Icon(
            Icons.Filled.CheckCircle, tr("thumbDlg.current"), Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TileLabel(label: String, sub: String, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
        Text(sub, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Placeholder for a source not yet loaded: blurred image, source icon and action. */
@Composable
private fun SourceTile(
    state: AppState, item: Item, backdrop: String?, label: String, action: String, brand: Color, letter: String,
    iconHash: String?, loading: Boolean, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val bmp = rememberItemImage(state, item, backdrop)
    val icon = state.favicon(iconHash)
    Box(
        Modifier.width(216.dp).aspectRatio(4f / 3f).clip(shape).border(1.dp, brand.copy(alpha = 0.5f), shape)
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(brand.copy(alpha = 0.55f), Palette.Surface))))
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize().blur(18.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Background.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = brand)
                    icon != null -> Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                    else -> Box(Modifier.size(34.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                        Text(letter, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(if (loading) tr("common.loading") else action, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}
