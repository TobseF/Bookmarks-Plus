package de.bookmarksplus.android.ui

import de.bookmarksplus.android.R
import de.bookmarksplus.android.str
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.android.BookmarkStore
import de.bookmarksplus.android.ShareActivity
import de.bookmarksplus.data.FolderInfo
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.Library
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------------------------------------ Actions

/** Opens the address in the device's default browser. */
fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, str(R.string.open_no_app, url), Toast.LENGTH_SHORT).show()
    }
}

/** Pass the link on to other apps (the own "Save to inbox" action is hidden). */
fun shareUrl(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
    val chooser = Intent.createChooser(send, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ShareActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    // from Android 13 the system itself shows a confirmation
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, str(R.string.common_copied), Toast.LENGTH_SHORT).show()
}

fun formatDate(epochSeconds: Long?): String? =
    epochSeconds?.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }

fun formatStars(n: Long): String = when {
    n >= 1_000_000 -> str(R.string.stars_millions).format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

// ------------------------------------------------------------------------------------------------ Building blocks

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text, modifier, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Icon of the bookmark; without an icon a letter, or for GitHub projects a code symbol. */
@Composable
fun ItemIcon(store: BookmarkStore, item: Item, size: Dp = 36.dp) {
    val img = rememberIcon(store, item.iconHash)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Palette.SurfaceHighest),
        contentAlignment = Alignment.Center,
    ) {
        when {
            img != null -> Image(img, null, Modifier.size(size * 0.7f), contentScale = ContentScale.Fit)
            item.iconIsGithubFallback -> Icon(Icons.Default.Code, null, Modifier.size(size * 0.55f), tint = Palette.TextMuted)
            else -> Text(
                (item.displayDomain ?: item.displayTitle).removePrefix("www.").take(1).uppercase(),
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

@Composable
private fun TagDots(lib: Library, item: Item) {
    val tags = lib.tagsOf[item.id].orEmpty().mapNotNull { lib.tagsById[it] }.take(4)
    if (tags.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.forEach { t ->
            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(t.color)))
        }
    }
}

@Composable
private fun MetaLine(lib: Library, item: Item) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.displayDomain?.let { Muted(it.removePrefix("www."), Modifier.weight(1f, fill = false)) }
        item.stars?.takeIf { it > 0 }?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, Modifier.size(12.dp), tint = Palette.Star)
                Spacer(Modifier.width(2.dp))
                Muted(formatStars(it))
            }
        }
        if (item.isDead) Text(str(R.string.health_dead), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TagDots(lib, item)
    }
}

@Composable
fun ItemRow(store: BookmarkStore, lib: Library, item: Item, onClick: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemIcon(store, item)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(2.dp))
            MetaLine(lib, item)
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, str(R.string.open_in_browser), tint = Palette.TextMuted)
        }
    }
}

@Composable
fun ItemCard(store: BookmarkStore, lib: Library, item: Item, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.medium, color = Palette.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                val img = rememberPreview(item, maxWidth = 480)
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else ItemIcon(store, item, size = 48.dp)
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(item.displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(2.dp))
                MetaLine(lib, item)
            }
        }
    }
}

@Composable
fun FolderRow(lib: Library, folder: FolderInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Text(folder.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted((lib.recursiveCounts[folder.id] ?: 0).toString())
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextFaint)
    }
}

@Composable
fun EmptyState(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.size(16.dp)); action() }
    }
}
