package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookCover
import com.leatherbound.listening.ui.components.EmptyPage
import com.leatherbound.listening.ui.components.Shelf
import com.leatherbound.listening.ui.components.formatDuration
import com.leatherbound.listening.ui.components.giltFrame
import com.leatherbound.listening.ui.components.paper
import com.leatherbound.listening.ui.theme.Leather
import com.leatherbound.listening.ui.theme.LocalWideLayout
import java.time.LocalTime

/** The landing tab: pick up the current book in one tap. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: Navigator) {
    val inProgress by vm.inProgress.collectAsStateWithLifecycle()
    val list by vm.listeningList.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val currentId = player.bookId ?: inProgress.firstOrNull()?.id
    val current by remember(currentId) { currentId?.let { vm.book(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val recent = remember(books) { books.sortedByDescending { it.addedAt }.take(20) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    val syncing by vm.syncing.collectAsStateWithLifecycle()
    // Pull down to sync with your servers.
    PullToRefreshBox(isRefreshing = syncing, onRefresh = { vm.sync() }, modifier = Modifier.fillMaxSize().paper().statusBarsPadding()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val greeting = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }
        val name = settings.displayName
        Text(if (name.isBlank()) greeting else "$greeting, $name", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 20.dp))
        Text("Where were we?", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(16.dp))
        if (!com.leatherbound.listening.ui.components.LocalAvailability.current.online) {
            Text(
                "You're offline. Downloaded books and books on this phone can still play.",
                style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (books.isEmpty()) {
            EmptyPage(
                "Your shelves are bare",
                "Connect Plex or Jellyfin, or choose a folder of audiobooks on this device.",
                action = { Button(onClick = nav::settings) { Text("Add a library") } },
            )
            return@Column
        }

        current?.let { book ->
            ResumeCard(book, player.bookId == book.id && player.isPlaying, if (player.bookId == book.id) player.positionMs else book.positionMs,
                onResume = { if (player.bookId == book.id) vm.player.togglePlayPause() else vm.play(book.id) },
                onOpen = { if (player.bookId == book.id) nav.player() else nav.book(book.id) })
        }

        Spacer(Modifier.height(8.dp))
        Shelf("Continue listening", inProgress.filter { it.id != current?.id }, { nav.book(it.id) })
        Shelf("Up next", list, { nav.book(it.id) })
        Shelf("Recently added", recent, { nav.book(it.id) })
        Spacer(Modifier.height(24.dp))
    }
    }
}

@Composable
private fun ResumeCard(book: BookEntity, playing: Boolean, positionMs: Long, onResume: () -> Unit, onOpen: () -> Unit) {
    val wide = LocalWideLayout.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(
            onClick = onOpen,
            colors = CardDefaults.cardColors(containerColor = Leather.Oxblood, contentColor = Leather.PaleGilt),
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp).fillMaxWidth(),
        ) {
            Row(Modifier.giltFrame().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.width(if (wide) 160.dp else 112.dp), elevation = 10.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CONTINUE", style = MaterialTheme.typography.labelMedium, color = Leather.Gilt)
                    Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(book.author, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = Leather.Gilt)
                    Spacer(Modifier.height(6.dp))
                    val progress = if (book.durationMs > 0) positionMs.toFloat() / book.durationMs else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = Leather.Gilt, trackColor = Leather.DarkLeather.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${formatDuration(book.durationMs - positionMs)} left", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onResume,
                        colors = ButtonDefaults.buttonColors(containerColor = Leather.Gilt, contentColor = Leather.DarkLeather),
                    ) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (playing) "Pause" else "Resume")
                    }
                }
            }
        }
    }
}
