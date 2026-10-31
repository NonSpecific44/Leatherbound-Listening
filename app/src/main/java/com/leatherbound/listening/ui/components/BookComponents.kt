package com.leatherbound.listening.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.CloudOff
import coil.compose.SubcomposeAsyncImage
import com.leatherbound.listening.data.ArtworkProvider
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.ui.Availability
import com.leatherbound.listening.data.db.DownloadState
import com.leatherbound.listening.ui.theme.Leather

/** What a long-press on a book can do; provided at the root of the app. */
class BookActions(
    val play: (String) -> Unit,
    val toggleList: (String) -> Unit,
    val setFinished: (String, Boolean) -> Unit,
    val download: (String) -> Unit,
    val removeDownload: (String) -> Unit,
)

val LocalBookActions = compositionLocalOf<BookActions?> { null }

/** Long-press menu: play, Up Next, finished, download. */
@Composable
private fun BookMenu(book: BookEntity, expanded: Boolean, onDismiss: () -> Unit) {
    val actions = LocalBookActions.current ?: return
    val availability = LocalAvailability.current
    DropdownMenu(expanded, onDismissRequest = onDismiss) {
        Text(book.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).width(220.dp))
        if (availability.canPlay(book)) DropdownMenuItem(
            text = { Text(if (book.inProgress) "Resume" else if (book.isFinished) "Listen again" else "Play") },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
            onClick = { actions.play(book.id); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (book.onListeningList) "Remove from Up Next" else "Add to Up Next") },
            leadingIcon = { Icon(if (book.onListeningList) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkAdd, null) },
            onClick = { actions.toggleList(book.id); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (book.isFinished) "Mark as unfinished" else "Mark as finished") },
            leadingIcon = { Icon(if (book.isFinished) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle, null) },
            onClick = { actions.setFinished(book.id, !book.isFinished); onDismiss() },
        )
        if (book.sourceId !in availability.localSources) {
            if (book.downloadState == DownloadState.NONE || book.downloadState == DownloadState.FAILED) DropdownMenuItem(
                text = { Text("Download") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = { actions.download(book.id); onDismiss() },
            ) else DropdownMenuItem(
                text = { Text(if (book.downloadState == DownloadState.DONE) "Remove download" else "Cancel download") },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { actions.removeDownload(book.id); onDismiss() },
            )
        }
    }
}

/** Tap to open, long-press for the quick menu (with a buzz). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.bookClicks(onClick: () -> Unit, onLongPress: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    return combinedClickable(onClick = onClick, onLongClick = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPress()
    })
}

/** Whether books can play right now; provided at the root of the app. */
val LocalAvailability = compositionLocalOf { Availability() }

/** Gilt double rule with the notched corners from the app icon's cover panel. */
fun Modifier.giltFrame(color: Color = Leather.Gilt, inset: Dp = 6.dp, notch: Dp = 10.dp): Modifier = drawWithContent {
    drawContent()
    drawNotchedFrame(color, inset.toPx(), notch.toPx(), 1.5.dp.toPx())
    drawNotchedFrame(color.copy(alpha = 0.6f), inset.toPx() + 4.dp.toPx(), (notch.toPx() - 2.dp.toPx()).coerceAtLeast(0f), 0.75.dp.toPx())
}

private fun DrawScope.drawNotchedFrame(color: Color, inset: Float, notch: Float, width: Float) {
    val r = Rect(inset, inset, size.width - inset, size.height - inset)
    if (r.width <= notch * 2 || r.height <= notch * 2) return
    val path = Path().apply {
        moveTo(r.left + notch, r.top)
        lineTo(r.right - notch, r.top)
        arcTo(Rect(Offset(r.right, r.top), notch), 180f, -90f, false)
        lineTo(r.right, r.bottom - notch)
        arcTo(Rect(Offset(r.right, r.bottom), notch), 270f, -90f, false)
        lineTo(r.left + notch, r.bottom)
        arcTo(Rect(Offset(r.left, r.bottom), notch), 0f, -90f, false)
        lineTo(r.left, r.top + notch)
        arcTo(Rect(Offset(r.left, r.top), notch), 90f, -90f, false)
        close()
    }
    drawPath(path, color, style = Stroke(width))
}

/** Subtle page background: parchment with a soft vignette. */
fun Modifier.paper(): Modifier = drawBehind {
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.06f)), center = center, radius = size.maxDimension * 0.75f))
}

/** A book cover drawn as a bound volume: rounded fore-edge, shaded spine, drop shadow. */
@Composable
fun BookCover(book: BookEntity, modifier: Modifier = Modifier, elevation: Dp = 6.dp) {
    val shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp)
    val available = LocalAvailability.current.canPlay(book)
    Box(
        modifier
            .aspectRatio(1f)
            .alpha(if (available) 1f else 0.45f)
            .shadow(elevation, shape)
            .clip(shape)
            .background(Leather.Oxblood)
    ) {
        SubcomposeAsyncImage(
            model = ArtworkProvider.uri(book),
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            error = { LeatherPlaceholder(book) },
            loading = { LeatherPlaceholder(book) },
        )
        // Spine: a darker band with a highlight, so covers read as books rather than album art.
        Box(
            Modifier.fillMaxHeight().width(10.dp).background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.White.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.15f), Color.Transparent))
            )
        )
        if (book.isFinished || book.downloadState == DownloadState.DONE || !available) {
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!available) Badge(Icons.Filled.CloudOff)
                if (book.downloadState == DownloadState.DONE) Badge(Icons.Filled.DownloadDone)
                if (book.isFinished) Badge(Icons.Filled.CheckCircle)
            }
        }
        if (book.inProgress) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.4f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(book.progress).background(Leather.Gilt))
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, null, tint = Leather.PaleGilt, modifier = Modifier.size(20.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(2.dp))
}

/** Tooled-leather stand-in for books without artwork. */
@Composable
fun LeatherPlaceholder(book: BookEntity) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Leather.Saddle, Leather.Oxblood, Color(0xFF4A2716))))
            .giltFrame(inset = 8.dp, notch = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
            Text(book.title, color = Leather.PaleGilt, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(book.author, color = Leather.Gilt, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Grid tile: cover, title, author. */
@Composable
fun BookTile(book: BookEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.bookClicks(onClick) { menu = true }.padding(6.dp)) {
        BookMenu(book, menu) { menu = false }
        BookCover(book, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.author, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** List row: small cover with metadata. */
@Composable
fun BookRow(book: BookEntity, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().bookClicks(onClick) { menu = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BookMenu(book, menu) { menu = false }
        BookCover(book, Modifier.size(64.dp), elevation = 3.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.author, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val meta = listOfNotNull(
                book.seriesName?.let { s -> listOfNotNull(s, ordinalSeries(book.seriesIndex)).joinToString(" · ") },
                book.year?.toString(),
                formatDuration(book.durationMs),
                if (book.inProgress) "${(book.progress * 100).toInt()}%" else null,
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A horizontal "shelf" of books under an ornamented heading. */
@Composable
fun Shelf(title: String, books: List<BookEntity>, onOpen: (BookEntity) -> Unit, tileWidth: Dp = 132.dp) {
    if (books.isEmpty()) return
    Column {
        Ornament(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
            items(books, key = { it.id }) { BookTile(it, { onOpen(it) }, Modifier.width(tileWidth)) }
        }
        ShelfBoard()
    }
}

/** The wooden ledge under a shelf. */
@Composable
fun ShelfBoard() {
    Canvas(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 12.dp)) {
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF7A4E2D), Color(0xFF4B2E1A))),
            size = Size(size.width, 6.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
        drawRect(Color.Black.copy(alpha = 0.18f), topLeft = Offset(0f, 6.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
    }
}

/** Section heading flanked by gilt rules and a fleuron. */
@Composable
fun Ornament(text: String, modifier: Modifier = Modifier) {
    val gilt = MaterialTheme.colorScheme.secondary
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            drawLine(gilt, Offset(0f, size.height / 2 - 2), Offset(size.width - 14.dp.toPx(), size.height / 2 - 2), 1.dp.toPx())
            drawLine(gilt.copy(alpha = 0.5f), Offset(0f, size.height / 2 + 2), Offset(size.width - 14.dp.toPx(), size.height / 2 + 2), 0.5.dp.toPx())
            val c = Offset(size.width - 6.dp.toPx(), size.height / 2)
            val d = 4.dp.toPx()
            drawPath(Path().apply { moveTo(c.x, c.y - d); lineTo(c.x + d, c.y); lineTo(c.x, c.y + d); lineTo(c.x - d, c.y); close() }, gilt)
        }
    }
}

@Composable
fun EmptyPage(title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("❦", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.secondary)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}
