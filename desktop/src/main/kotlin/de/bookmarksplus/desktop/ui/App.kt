package de.bookmarksplus.desktop.ui

import de.bookmarksplus.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import de.bookmarksplus.desktop.AppState
import de.bookmarksplus.desktop.ViewMode
import kotlinx.coroutines.delay

@Composable
fun App(state: AppState, searchFocus: FocusRequester, onImport: () -> Unit, onExport: () -> Unit) {
    AppTheme(state.accent) {
        // default scrollbar is barely visible in the dark theme
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.LocalContextMenuRepresentation provides androidx.compose.foundation.DefaultContextMenuRepresentation(
                backgroundColor = Palette.SurfaceHighest, textColor = Palette.Text, itemHoverColor = Palette.Outline,
            ),
            androidx.compose.foundation.LocalScrollbarStyle provides androidx.compose.foundation.defaultScrollbarStyle().copy(
                thickness = 10.dp, minimalHeight = 40.dp,
                unhoverColor = Palette.TextFaint.copy(alpha = 0.55f), hoverColor = Palette.TextMuted,
                shape = RoundedCornerShape(5.dp),
            ),
        ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(state, Modifier.width(272.dp))
                VerticalDivider(color = Palette.OutlineSoft)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(state, searchFocus, onImport, onExport)
                    FilterBar(state)
                    Box(Modifier.weight(1f)) {
                        if (state.library.items.isEmpty()) {
                            EmptyState(tr("app.empty"))
                        } else when (state.viewMode) {
                            ViewMode.List -> ListView(state)
                            ViewMode.Tree -> if (state.filter.trash) ListView(state) else TreeView(state)
                            ViewMode.Cards -> CardsView(state)
                        }
                    }
                }
                if (state.selection.isNotEmpty()) {
                    VerticalDivider(color = Palette.OutlineSoft)
                    DetailPane(state, Modifier.width(360.dp))
                }
            }
            state.message?.let { msg ->
                val action = state.messageAction
                LaunchedEffect(msg, action) { delay(if (action != null) 7000 else 4000); state.message = null; state.messageAction = null }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).clip(RoundedCornerShape(10.dp))
                        .background(Palette.SurfaceHighest).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, color = Palette.Text)
                    if (action != null) Text(
                        action.first, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp).clip(RoundedCornerShape(4.dp))
                            .clickable { state.message = null; state.messageAction = null; action.second() }.padding(2.dp),
                    )
                }
            }
            state.drag?.let { d ->
                Row(
                    Modifier.offset { androidx.compose.ui.unit.IntOffset(d.x.toInt() + 14, d.y.toInt() + 10) }
                        .clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHighest).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(d.label.take(50), color = Palette.Text, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
                    Text(
                        if (state.dropHover != null) "  → ${state.library.folders[state.dropHover]?.title}" else tr("app.dragHint"),
                        color = if (state.dropHover != null) MaterialTheme.colorScheme.primary else Palette.TextFaint,
                        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                    )
                }
            }
            if (state.showSettings) SettingsDialog(state, onImport, onExport)
            state.thumbPicker?.let { id -> state.library.anyItem(id)?.let { ThumbnailDialog(state, it) } }
            Dialogs(state)
        }
        }
        }
    }
}
