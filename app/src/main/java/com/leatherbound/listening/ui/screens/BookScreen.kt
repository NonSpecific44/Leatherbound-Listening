package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.BookmarkEntity
import androidx.compose.material.icons.filled.Bookmark
import com.leatherbound.listening.data.db.ChapterEntity
import com.leatherbound.listening.data.db.DownloadState
import com.leatherbound.listening.data.db.SourceType
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookCover
import com.leatherbound.listening.ui.components.Ornament
import com.leatherbound.listening.ui.components.Shelf
import com.leatherbound.listening.ui.components.formatClock
import com.leatherbound.listening.ui.components.formatDuration
import com.leatherbound.listening.ui.components.ordinalSeries
import com.leatherbound.listening.ui.components.paper
import com.leatherbound.listening.ui.theme.LocalWideLayout

@Composable
fun BookScreen(vm: AppViewModel, nav: Navigator, bookId: String) {
    val book by remember(bookId) { vm.book(bookId) }.collectAsState(null)
    val chapters by remember(bookId) { vm.chapters(bookId) }.collectAsState(emptyList())
    val sources by vm.sources.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    LaunchedEffect(bookId) { vm.openBook(bookId) }
    val b = book ?: return
    val series by remember(b.seriesName) { vm.series(b) }.collectAsState(emptyList())
    val isLocal = sources.firstOrNull { it.id == b.sourceId }?.type == SourceType.LOCAL
    val position = if (player.bookId == b.id) player.positionMs else b.positionMs
    val wide = LocalWideLayout.current
    val bookmarks by remember(bookId) { vm.bookmarks(bookId) }.collectAsState(emptyList())
    val onAuthor = { nav.author(b.author) }
    val onSeries = { b.seriesName?.let { nav.series(it) } ?: Unit }
    val playFrom: (Long) -> Unit = { vm.play(b.id, it) }

    Column(Modifier.fillMaxSize().paper().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        if (wide) {
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.width(300.dp)) {
                    BookCover(b, Modifier.fillMaxWidth(), elevation = 14.dp)
                    Spacer(Modifier.height(20.dp))
                    Actions(vm, b, isLocal, position)
                }
                Column(Modifier.weight(1f)) {
                    Header(b, position, onAuthor = onAuthor, onSeries = onSeries)
                    Description(b)
                    Bookmarks(bookmarks, playFrom, vm::deleteBookmark)
                    Contents(chapters, position, player.bookId == b.id) { vm.play(b.id, it.startMs) }
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BookCover(b, Modifier.widthIn(max = 260.dp).fillMaxWidth(0.7f), elevation = 14.dp)
                Spacer(Modifier.height(20.dp))
                Header(b, position, center = true, onAuthor = onAuthor, onSeries = onSeries)
                Spacer(Modifier.height(12.dp))
                Actions(vm, b, isLocal, position)
                Description(b)
                Bookmarks(bookmarks, playFrom, vm::deleteBookmark)
                Contents(chapters, position, player.bookId == b.id) { vm.play(b.id, it.startMs) }
            }
        }
        if (series.size > 1) {
            Spacer(Modifier.height(16.dp))
            Shelf("In the ${b.seriesName} series", series, { if (it.id != b.id) nav.book(it.id) })
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Header(b: BookEntity, position: Long, center: Boolean = false, onAuthor: () -> Unit, onSeries: () -> Unit) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        b.seriesName?.let {
            Text(
                listOfNotNull(it, ordinalSeries(b.seriesIndex)).joinToString(" · ").uppercase(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, textAlign = align,
                modifier = Modifier.clickable(onClick = onSeries).padding(vertical = 4.dp),
            )
        }
        Text(b.title, style = MaterialTheme.typography.headlineMedium, textAlign = align)
        Text(
            "by ${b.author}", style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Normal,
            textAlign = align, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onAuthor).padding(vertical = 2.dp),
        )
        b.narrator?.let { Text("read by $it", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align) }
        Spacer(Modifier.height(6.dp))
        val details = listOfNotNull(b.year?.toString(), formatDuration(b.durationMs),
            when {
                b.isFinished -> "Finished"
                position > 0 -> "${formatDuration(b.durationMs - position)} left"
                else -> null
            })
        Text(details.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(vm: AppViewModel, b: BookEntity, isLocal: Boolean, position: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val available = com.leatherbound.listening.ui.components.LocalAvailability.current.canPlay(b)
        Button(onClick = { vm.play(b.id) }, enabled = available, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp)) {
            Icon(Icons.Filled.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(when { !available -> "Offline — not downloaded"; b.isFinished -> "Listen again"; position > 0 -> "Resume"; else -> "Begin" })
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.toggleList(b.id) }) {
                Icon(if (b.onListeningList) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (b.onListeningList) "On list" else "Up next")
            }
            OutlinedButton(onClick = { vm.setFinished(b.id, !b.isFinished) }) {
                Icon(if (b.isFinished) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (b.isFinished) "Finished" else "Mark finished")
            }
            if (!isLocal) when (b.downloadState) {
                DownloadState.NONE -> OutlinedButton(onClick = { vm.download(b.id) }) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Download")
                }
                DownloadState.QUEUED, DownloadState.DOWNLOADING -> OutlinedButton(onClick = { vm.removeDownload(b.id) }) {
                    CircularProgressIndicator(progress = { b.downloadProgress }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(if (b.downloadState == DownloadState.QUEUED) "Waiting…" else "${(b.downloadProgress * 100).toInt()}% · Cancel")
                }
                DownloadState.DONE -> OutlinedButton(onClick = { vm.removeDownload(b.id) }) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove download")
                }
                DownloadState.FAILED -> OutlinedButton(onClick = { vm.download(b.id) }) {
                    Icon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry download")
                }
            }
        }
    }
}

@Composable
private fun Description(b: BookEntity) {
    val text = b.description?.takeIf { it.isNotBlank() } ?: return
    var expanded by remember { mutableStateOf(false) }
    Spacer(Modifier.height(20.dp))
    Ornament("Synopsis")
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = if (expanded) Int.MAX_VALUE else 6,
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
    )
    if (!expanded) TextButton(onClick = { expanded = true }) { Text("Read more") }
}

/** Saved places in the book, with their notes. */
@Composable
fun Bookmarks(bookmarks: List<BookmarkEntity>, onPlay: (Long) -> Unit, onDelete: (Long) -> Unit) {
    if (bookmarks.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Ornament("Bookmarks")
    bookmarks.forEach { m ->
        Row(Modifier.fillMaxWidth().clickable { onPlay(m.positionMs) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bookmark, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(formatClock(m.positionMs), style = MaterialTheme.typography.titleSmall)
                if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            }
            IconButton(onClick = { onDelete(m.id) }) { Icon(Icons.Filled.Delete, "Delete bookmark", Modifier.size(20.dp)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Table of contents, styled like the front matter of a book. */
@Composable
fun Contents(chapters: List<ChapterEntity>, positionMs: Long, isCurrentBook: Boolean, onChapter: (ChapterEntity) -> Unit) {
    if (chapters.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Ornament("Contents")
    val current = chapters.lastOrNull { it.startMs <= positionMs }?.index
    chapters.forEach { c ->
        val here = isCurrentBook && c.index == current || !isCurrentBook && positionMs > 0 && c.index == current
        Row(
            Modifier.fillMaxWidth().clickable { onChapter(c) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "${c.index + 1}.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(36.dp),
            )
            Text(
                c.title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(formatClock(c.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
