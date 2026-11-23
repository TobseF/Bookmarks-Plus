package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.data.Item
import de.bookmarksplus.data.TagInfo
import de.bookmarksplus.desktop.AppState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault())

fun formatDate(epochSeconds: Long?): String =
    if (epochSeconds == null || epochSeconds <= 0) "–" else DATE.format(Instant.ofEpochSecond(epochSeconds))

fun formatRelative(epochSeconds: Long?, now: Long = System.currentTimeMillis() / 1000): String {
    if (epochSeconds == null || epochSeconds <= 0) return "–"
    val d = (now - epochSeconds) / 86_400
    return when {
        d < 1 -> tr("date.today")
        d < 2 -> tr("date.yesterday")
        d < 30 -> tr("date.daysAgo", d)
        d < 365 -> tr("date.monthsAgo", d / 30)
        else -> tr("date.yearsAgo", d / 365)
    }
}

fun formatStars(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

/** Stable color per domain for placeholders. */
fun domainColor(domain: String?): Color {
    val h = ((domain ?: "?").hashCode() and 0x7FFFFFFF) % 360
    return Color.hsl(h.toFloat(), 0.45f, 0.42f)
}

/** Official GitHub logo (Octicon "mark-github", 16×16) as a fallback when no other icon is available. */
internal val GithubMark: ImageVector by lazy {
    ImageVector.Builder("GitHub", 16.dp, 16.dp, 16f, 16f).addPath(
        addPathNodes(
            "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0 0 16 8c0-4.42-3.58-8-8-8z",
        ),
        fill = SolidColor(Color.White),
    ).build()
}

/**
 * Icon of a bookmark, in this order: Flathub → AlternativeTo → favicon of the (stored) website →
 * GitHub avatar → official GitHub logo (GitHub projects only) → letter on domain color.
 */
@Composable
fun Favicon(state: AppState, item: Item, size: Dp = 16.dp) {
    val bmp = state.favicon(item.iconHash)
    if (bmp != null) {
        // avatars are square photos: slightly round, logos keep the small corner
        val shape = if (item.iconHash == item.avatarHash) RoundedCornerShape(size / 4) else RoundedCornerShape(3.dp)
        Image(bmp, null, Modifier.size(size).clip(shape), filterQuality = FilterQuality.Medium)
    } else if (item.iconIsGithubFallback) {
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF24292F)), contentAlignment = Alignment.Center) {
            Image(rememberVectorPainter(GithubMark), null, Modifier.size(size * 0.78f))
        }
    } else {
        val c = domainColor(item.domain)
        Box(
            Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(c.tint(0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (item.domain ?: item.title).firstOrNull()?.uppercase() ?: "?",
                fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
fun TagChip(tag: TagInfo, small: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    val c = Color(tag.color.toInt())
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.tint(0.22f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = if (small) 7.dp else 9.dp, vertical = if (small) 1.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(if (small) 6.dp else 7.dp).clip(CircleShape).background(c))
        Text(tag.label, fontSize = if (small) 11.sp else 12.sp, color = Palette.Text, maxLines = 1)
        trailing?.invoke()
    }
}

@Composable
fun StarsBadge(stars: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = Palette.Star, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(2.dp))
        Text(formatStars(stars), fontSize = 12.sp, color = Palette.TextMuted)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text, modifier, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Image paths in display priority: for GitHub repos first the GitHub card, otherwise first the screenshot of the page,
 * then the share image (og:image).
 */
fun imageCandidates(item: Item): List<String> =
    if (item.chosenThumbPath != null) listOfNotNull(item.chosenThumbPath, item.thumbnailPath, item.cardImagePath)
    else if (item.domain == "github.com") listOfNotNull(item.cardImagePath, item.thumbnailPath)
    else listOfNotNull(item.thumbnailPath, item.cardImagePath)

/** Loads an image in the background and shows it as soon as it is there. */
@Composable
fun rememberItemImage(state: AppState, item: Item, path: String? = imageCandidates(item).firstOrNull()): ImageBitmap? {
    if (path == null) return null
    val key = "$path@${item.scrapedAt}"
    // produceState keeps the old value when the key changes, so store the value together with the key and show only matching ones
    val loaded by produceState<Pair<String, ImageBitmap?>>(key to state.cachedImage(key), key) {
        if (value.second == null || value.first != key) {
            value = key to (state.cachedImage(key) ?: withContext(Dispatchers.IO) { state.loadImage(key, path) })
        }
    }
    return loaded.takeIf { it.first == key }?.second ?: state.cachedImage(key)
}

/** Preview image of a card; without an image a placeholder with favicon. */
@Composable
fun CardImage(state: AppState, item: Item, modifier: Modifier = Modifier, path: String? = imageCandidates(item).firstOrNull()) {
    val bmp = rememberItemImage(state, item, path)
    Box(modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            val c = domainColor(item.domain)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(c.copy(alpha = 0.55f).tint(0.9f), Palette.SurfaceHigh)),
                ),
            )
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Background.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { Favicon(state, item, 26.dp) }
        }
    }
}

@Composable
fun rememberHover(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    return source to hovered
}

fun Modifier.hover(source: MutableInteractionSource) = hoverable(source)

/**
 * Plain input field without Material frame. Placeholder and input use the same text style with a fixed,
 * centered line height – otherwise the cursor sits higher than the placeholder text (Material typography vs. bare style).
 */
@Composable
fun PlainField(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val style = plainFieldStyle(fontSize)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty() && placeholder != null) {
                    Text(placeholder, style = style.copy(color = Palette.TextFaint), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}

@Composable
fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    // keep selection/cursor across recompositions, text comes from outside
    var tfv by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    val shown = if (tfv.text == value) tfv else androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    PlainField(shown, { tfv = it; if (it.text != value) onValueChange(it.text) }, modifier, placeholder, fontSize, keyboardOptions, keyboardActions)
}

fun plainFieldStyle(fontSize: androidx.compose.ui.unit.TextUnit, color: androidx.compose.ui.graphics.Color = Palette.Text) =
    androidx.compose.ui.text.TextStyle(
        color = color,
        fontSize = fontSize,
        lineHeight = fontSize * 1.15f,
        baselineShift = androidx.compose.ui.text.style.BaselineShift(0.2f),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
        ),
    )

/** GitHub icon (like the directory icons of Flathub/AlternativeTo); clickable with [onClick]. */
@Composable
fun GithubBadge(size: Dp = 16.dp, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF24292F))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Image(rememberVectorPainter(GithubMark), "GitHub", Modifier.size(size * 0.78f)) }
}
