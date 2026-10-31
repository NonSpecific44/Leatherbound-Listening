package com.leatherbound.listening.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.data.db.DownloadState
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookRow
import com.leatherbound.listening.ui.components.EmptyPage
import com.leatherbound.listening.ui.components.paper

@Composable
fun DownloadsScreen(vm: AppViewModel, nav: Navigator) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val total = downloads.sumOf { it.bytes }
    val finished = downloads.filter { it.book.isFinished }

    Column(Modifier.fillMaxSize().paper().statusBarsPadding()) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "${downloads.size} ${if (downloads.size == 1) "book" else "books"} · ${Formatter.formatShortFileSize(context, total)} used",
            style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (finished.isNotEmpty()) {
            OutlinedButton(onClick = { vm.removeFinishedDownloads() }, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Remove ${finished.size} finished (${Formatter.formatShortFileSize(context, finished.sumOf { it.bytes })})")
            }
        }
        if (downloads.isEmpty()) {
            EmptyPage("Nothing downloaded", "Download a book from its page to listen without a connection.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(downloads, key = { it.book.id }) { (book, bytes) ->
                BookRow(book, { nav.book(book.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (book.downloadState) {
                            DownloadState.DONE -> Text(Formatter.formatShortFileSize(context, bytes), style = MaterialTheme.typography.labelMedium)
                            DownloadState.FAILED -> Text("Failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            else -> CircularProgressIndicator(progress = { book.downloadProgress }, modifier = Modifier.padding(4.dp))
                        }
                        IconButton(onClick = { vm.removeDownload(book.id) }) { Icon(Icons.Filled.Delete, "Remove download") }
                    }
                }
            }
        }
    }
}
