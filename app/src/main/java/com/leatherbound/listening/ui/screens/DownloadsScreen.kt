package com.leatherbound.listening.ui.screens

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.data.Downloads
import com.leatherbound.listening.data.db.DownloadState
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.DownloadedBook
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookCover
import com.leatherbound.listening.ui.components.BookRow
import com.leatherbound.listening.ui.components.ConfirmDialog
import com.leatherbound.listening.ui.components.EmptyPage
import com.leatherbound.listening.ui.components.LocalBookActions
import com.leatherbound.listening.ui.components.Ornament
import com.leatherbound.listening.ui.components.formatClock
import com.leatherbound.listening.ui.components.paper

/** Active downloads (with speed and size) on top, downloaded books below. */
@Composable
fun DownloadsScreen(vm: AppViewModel, nav: Navigator) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val actions = LocalBookActions.current
    val context = LocalContext.current
    var confirmFinished by remember { mutableStateOf(false) }

    val active = downloads.filter { it.book.downloadState == DownloadState.DOWNLOADING || it.book.downloadState == DownloadState.QUEUED }
    val failed = downloads.filter { it.book.downloadState == DownloadState.FAILED }
    val done = downloads.filter { it.book.downloadState == DownloadState.DONE }
    val finished = done.filter { it.book.isFinished }
    val total = done.sumOf { it.bytes }

    Column(Modifier.fillMaxSize().paper()) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "${done.size} downloaded · ${Formatter.formatShortFileSize(context, total)} on this phone" +
                if (active.isNotEmpty()) " · ${active.size} in progress" else "",
            style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (downloads.isEmpty()) {
            EmptyPage("Nothing downloaded", "Download a book from its page, or long-press it, to listen without a connection.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (active.isNotEmpty()) {
                item(key = "h-active") { Ornament("In progress", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(active, key = { "a-" + it.book.id }) { item ->
                    ActiveDownload(item, transfers[item.book.id], context, onCancel = { actions?.removeDownload?.invoke(item.book.id) })
                }
            }
            if (failed.isNotEmpty()) {
                item(key = "h-failed") { Ornament("Failed", Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(failed, key = { "f-" + it.book.id }) { (book, _) ->
                    BookRow(book, { nav.book(book.id) }) {
                        OutlinedButton(onClick = { vm.download(book.id) }) { Text("Retry") }
                    }
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h-done") {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ornament("Downloaded", Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp))
                        if (finished.isNotEmpty()) OutlinedButton(onClick = { confirmFinished = true }) { Text("Remove finished") }
                    }
                }
                items(done, key = { "d-" + it.book.id }) { (book, bytes) ->
                    BookRow(book, { nav.book(book.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Formatter.formatShortFileSize(context, bytes), style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { actions?.removeDownload?.invoke(book.id) }) { Icon(Icons.Filled.Delete, "Delete download") }
                        }
                    }
                }
            }
        }
    }

    if (confirmFinished) {
        ConfirmDialog(
            title = "Remove finished downloads?",
            message = "Delete the downloaded copies of ${finished.size} finished ${if (finished.size == 1) "book" else "books"} " +
                "(${Formatter.formatShortFileSize(context, finished.sumOf { it.bytes })})? You can still stream them.",
            confirm = "Delete",
            onConfirm = { vm.removeFinishedDownloads() },
            onDismiss = { confirmFinished = false },
        )
    }
}

/** "48 MB of 312 MB · 3.1 MB/s · 1:25 left · file 2 of 14" with a progress bar. */
@Composable
private fun ActiveDownload(item: DownloadedBook, transfer: Downloads.Transfer?, context: Context, onCancel: () -> Unit) {
    val book = item.book
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, Modifier.size(56.dp), elevation = 2.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.padding(top = 4.dp))
            if (book.downloadState == DownloadState.QUEUED || transfer == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (book.downloadState == DownloadState.QUEUED) "Waiting for a connection (Wi-Fi only is on in Settings)" else "Starting…",
                    style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Prefer real byte counts; fall back to the duration-based estimate if the server didn't give sizes.
                val fraction = transfer.bytesTotal?.let { (transfer.bytesDone.toFloat() / it).coerceIn(0f, 1f) } ?: book.downloadProgress
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                val size = { b: Long -> Formatter.formatShortFileSize(context, b) }
                val total = transfer.bytesTotal ?: if (book.downloadProgress > 0.02f) (transfer.bytesDone / book.downloadProgress).toLong() else null
                val left = if (total != null && transfer.bytesPerSecond > 0) (total - transfer.bytesDone) * 1000 / transfer.bytesPerSecond else null
                Text(
                    listOfNotNull(
                        size(transfer.bytesDone) + (total?.let { " of " + (if (transfer.bytesTotal == null) "~" else "") + size(it) } ?: ""),
                        "${size(transfer.bytesPerSecond)}/s",
                        left?.let { formatClock(it) + " left" },
                        if (transfer.fileCount > 1) "file ${transfer.file} of ${transfer.fileCount}" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel download") }
    }
}
