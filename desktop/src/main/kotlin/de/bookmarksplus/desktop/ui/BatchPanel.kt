package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bookmarksplus.desktop.AppState
import de.bookmarksplus.desktop.BatchKind
import de.bookmarksplus.desktop.BatchPhase
import de.bookmarksplus.desktop.BatchStatus

/**
 * Permanent status display at the bottom of the sidebar: the running batch with progress bar, "x of y",
 * pause/resume and cancel; a finished one stays as "done" until it is closed with ✕.
 */
@Composable
fun BatchPanel(state: AppState) {
    val b = state.batch
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(10.dp))
            .background(Palette.SurfaceHigh).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (b == null) {
            Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.HourglassEmpty, null, Modifier.size(14.dp), tint = Palette.TextFaint)
                Spacer(Modifier.width(6.dp))
                Text(tr("batch.noTask"), fontSize = 12.sp, color = Palette.TextFaint)
            }
            return@Column
        }
        val (icon, tint) = when (b.phase) {
            BatchPhase.Done -> Icons.Filled.CheckCircle to Color(0xFF4CC38A)
            BatchPhase.Cancelled -> Icons.Filled.Cancel to Palette.TextMuted
            BatchPhase.Running -> iconOf(b.kind) to (if (b.paused) Palette.TextMuted else accent)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(
                b.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (b.running) {
                if (b.paused) BatchButton(Icons.Filled.PlayArrow, tr("common.resume"), accent) { state.resumeBatch() }
                else BatchButton(Icons.Filled.Pause, tr("common.pause"), Palette.TextMuted) { state.pauseBatch() }
                BatchButton(Icons.Filled.Stop, tr("common.cancel"), Palette.TextMuted) { state.cancelBatch() }
            } else BatchButton(Icons.Filled.Close, tr("common.close"), Palette.TextMuted) { state.dismissBatch() }
        }
        Column(Modifier.padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            LinearProgressIndicator(
                progress = { if (b.phase == BatchPhase.Done) 1f else b.fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = when {
                    b.phase == BatchPhase.Done -> tint
                    b.phase == BatchPhase.Cancelled || b.paused -> Palette.TextFaint
                    else -> accent
                },
                trackColor = Palette.Outline,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("common.nOfM", b.done, b.total), fontSize = 11.sp, color = Palette.TextMuted)
                Text(
                    "  ·  " + statusText(b), fontSize = 11.sp, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (b.running && b.total > 0) Text("${(b.fraction * 100).toInt()} %", fontSize = 11.sp, color = Palette.TextFaint)
            }
            val detail = if (b.running) b.current else b.summary
            detail?.let { Text(it, fontSize = 11.sp, color = Palette.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp) }
            if (b.errors > 0) Text("${b.errors} fehlgeschlagen", fontSize = 11.sp, color = Palette.Star)
        }
    }
}

private fun statusText(b: BatchStatus): String = when (b.phase) {
    BatchPhase.Done -> (b.endedAt?.let { tr("batch.doneAfter", duration(it - b.startedAt)) } ?: tr("batch.done"))
    BatchPhase.Cancelled -> tr("batch.cancelled")
    BatchPhase.Running -> when {
        b.paused -> tr("batch.paused")
        b.step != null -> b.step
        else -> tr("batch.running")
    }
}

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${s % 3600 / 60} min"
    }
}

private fun iconOf(kind: BatchKind): ImageVector = when (kind) {
    BatchKind.Enrich -> Icons.Filled.Refresh
    BatchKind.Links -> Icons.Filled.LinkOff
    BatchKind.Ai -> Icons.Filled.AutoAwesome
    BatchKind.Apps -> Icons.Filled.Apps
    BatchKind.Sync -> Icons.Filled.CloudSync
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BatchButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Text(
                label, fontSize = 12.sp, color = Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHighest).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        delayMillis = 400,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(16.dp), tint = tint) }
    }
}
