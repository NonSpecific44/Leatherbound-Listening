package com.leatherbound.listening.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leatherbound.listening.R
import com.leatherbound.listening.ui.components.BookCover
import androidx.compose.runtime.CompositionLocalProvider
import com.leatherbound.listening.ui.components.LocalAvailability
import com.leatherbound.listening.ui.components.BookActions
import com.leatherbound.listening.ui.components.AppTopBar
import com.leatherbound.listening.ui.components.ConfirmDialog
import com.leatherbound.listening.ui.screens.CollectionScreen
import com.leatherbound.listening.ui.screens.GenreScreen
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.mutableStateOf
import com.leatherbound.listening.ui.components.LocalBookActions
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.leatherbound.listening.ui.screens.AuthorScreen
import com.leatherbound.listening.ui.screens.BookScreen
import com.leatherbound.listening.ui.screens.DownloadsScreen
import com.leatherbound.listening.ui.screens.SeriesScreen
import com.leatherbound.listening.ui.screens.HomeScreen
import com.leatherbound.listening.ui.screens.LibraryScreen
import com.leatherbound.listening.ui.screens.ListeningListScreen
import com.leatherbound.listening.ui.screens.PlayerScreen
import com.leatherbound.listening.ui.screens.SettingsScreen
import com.leatherbound.listening.ui.screens.StatsScreen
import com.leatherbound.listening.ui.theme.Leather
import com.leatherbound.listening.ui.theme.LocalWideLayout

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.AutoStories),
    Library("library", "Library", Icons.AutoMirrored.Filled.MenuBook),
    UpNext("list", "Up Next", Icons.Filled.Bookmarks),
    Stats("stats", "Stats", Icons.Filled.Insights),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

class Navigator(private val nav: NavHostController) {
    fun book(id: String) = nav.navigate("book/${android.net.Uri.encode(id)}")
    fun player() = nav.navigate("player") { launchSingleTop = true }
    fun author(name: String) = nav.navigate("author/${android.net.Uri.encode(name)}")
    fun series(name: String) = nav.navigate("series/${android.net.Uri.encode(name)}")
    fun downloads() = nav.navigate("downloads") { launchSingleTop = true }
    fun collection(id: String) = nav.navigate("collection/${android.net.Uri.encode(id)}")
    fun libraryCollections() = tab("library")
    fun genre(name: String) = nav.navigate("genre/${android.net.Uri.encode(name)}")
    fun settings() = tab("settings")
    fun back() = nav.popBackStack()
    fun tab(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** "A new version is available", with the release notes, until you install, skip or dismiss it. */
@Composable
private fun UpdatePrompt(vm: AppViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    val release = when (val u = update) {
        is UpdateState.Available -> u.release
        is UpdateState.Downloading -> u.release
        else -> return
    }
    val progress = (update as? UpdateState.Downloading)?.progress
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (progress == null) vm.dismissUpdate() },
        title = { Text("Version ${release.version} is available") },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                if (progress != null) {
                    Text("Downloading… ${(progress * 100).toInt()}%")
                    androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    release.notes.lines().joinToString("\n") { it.replace(Regex("^#+\\s*"), "").replace(Regex("^- "), "• ") }.ifBlank { "No release notes." },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { vm.installUpdate(release) }, enabled = progress == null) { Text("Install") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (progress == null) androidx.compose.material3.TextButton(onClick = { vm.skipUpdate(release.version) }) { Text("Skip this version") }
                androidx.compose.material3.TextButton(onClick = { vm.dismissUpdate() }) { Text(if (progress == null) "Later" else "Cancel") }
            }
        },
    )
}

@Composable
fun LeatherboundRoot(vm: AppViewModel) {
    val nav = rememberNavController()
    val navigator = remember(nav) { Navigator(nav) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val wide = LocalWideLayout.current
    val onPlayer = route == "player"
    val availability by vm.availability.collectAsStateWithLifecycle()

    val content: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(nav, startDestination = Tab.Home.route, modifier = modifier) {
            composable("home") { HomeScreen(vm, navigator) }
            composable("library") { LibraryScreen(vm, navigator) }
            composable("list") { ListeningListScreen(vm, navigator) }
            composable("stats") { StatsScreen(vm, navigator) }
            composable("settings") { SettingsScreen(vm, navigator) }
            composable("book/{id}") { e -> BookScreen(vm, navigator, android.net.Uri.decode(e.arguments?.getString("id").orEmpty())) }
            composable("player") { PlayerScreen(vm, navigator) }
            composable("author/{name}") { e -> AuthorScreen(vm, navigator, android.net.Uri.decode(e.arguments?.getString("name").orEmpty())) }
            composable("series/{name}") { e -> SeriesScreen(vm, navigator, android.net.Uri.decode(e.arguments?.getString("name").orEmpty())) }
            composable("downloads") { DownloadsScreen(vm, navigator) }
            composable("genre/{name}") { e -> GenreScreen(vm, navigator, android.net.Uri.decode(e.arguments?.getString("name").orEmpty())) }
            composable("collection/{id}") { e -> CollectionScreen(vm, navigator, android.net.Uri.decode(e.arguments?.getString("id").orEmpty())) }
        }
    }

    // Deleting a download always goes through one confirmation, wherever it was asked for.
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val bookActions = remember(vm) {
        BookActions(
            play = { vm.play(it) },
            toggleList = { vm.toggleList(it) },
            setFinished = { id, done -> vm.setFinished(id, done) },
            markUnread = { vm.markUnread(it) },
            download = { vm.download(it) },
            removeDownload = { confirmDelete = it },
        )
    }
    confirmDelete?.let { id ->
        val books by vm.books.collectAsStateWithLifecycle()
        val downloads by vm.downloads.collectAsStateWithLifecycle()
        val book = books.firstOrNull { it.id == id }
        val size = downloads.firstOrNull { it.book.id == id }?.bytes ?: 0L
        val finished = book?.downloadState == com.leatherbound.listening.data.db.DownloadState.DONE
        val context = androidx.compose.ui.platform.LocalContext.current
        ConfirmDialog(
            title = if (finished) "Delete download?" else "Cancel download?",
            message = (if (finished) "Delete the downloaded copy of “${book?.title}”" + (if (size > 0) " (${android.text.format.Formatter.formatShortFileSize(context, size)})" else "") + " from this phone?"
            else "Stop downloading “${book?.title}” and delete what has been saved so far?") +
                " Your listening progress is kept, and you can still stream it or download it again.",
            confirm = if (finished) "Delete" else "Cancel download",
            onConfirm = { vm.removeDownload(id) },
            onDismiss = { confirmDelete = null },
        )
    }

    UpdatePrompt(vm)

    // The top bar's downloads button shows what's in flight.
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val active = downloads.filter {
        it.book.downloadState == com.leatherbound.listening.data.db.DownloadState.QUEUED ||
            it.book.downloadState == com.leatherbound.listening.data.db.DownloadState.DOWNLOADING
    }
    val overall = active.takeIf { it.isNotEmpty() }?.map { it.book.downloadProgress }?.average()?.toFloat()?.takeIf { it > 0f }
    val topBar: @Composable () -> Unit = {
        if (!onPlayer) AppTopBar(active.size, overall, onDownloads = { navigator.downloads() }, onLogo = { navigator.tab(Tab.Home.route) })
    }
    // The bar handles the status bar, so pages below it shouldn't pad for it again.
    val body: @Composable (PaddingValues) -> Unit = { padding -> content(Modifier.padding(padding).consumeWindowInsets(padding)) }
    CompositionLocalProvider(LocalAvailability provides availability, LocalBookActions provides bookActions) {
    if (wide) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!onPlayer) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Spacer(Modifier.height(12.dp))
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = route == tab.route,
                            onClick = { navigator.tab(tab.route) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = topBar,
                bottomBar = { if (!onPlayer) MiniPlayer(vm, navigator) },
            ) { padding -> body(padding) }
        }
    } else {
        Scaffold(
            topBar = topBar,
            bottomBar = {
                if (!onPlayer) Column {
                    MiniPlayer(vm, navigator)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { navigator.tab(tab.route) },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                            )
                        }
                    }
                }
            },
            contentWindowInsets = if (onPlayer) WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        ) { padding -> body(padding) }
    }
    }
}

/** The ribbon at the bottom of every page showing what's playing. */
@Composable
private fun MiniPlayer(vm: AppViewModel, navigator: Navigator) {
    val state by vm.playerState.collectAsStateWithLifecycle()
    val bookId = state.bookId
    val book by remember(bookId) { bookId?.let { vm.book(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    AnimatedVisibility(book != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        val b = book ?: return@AnimatedVisibility
        Surface(color = Leather.Oxblood, contentColor = Leather.PaleGilt, tonalElevation = 4.dp) {
            Column {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Leather.DarkLeather)) {
                    val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Leather.Gilt))
                }
                // Tap or swipe up to open the full player.
                var lift by remember { mutableFloatStateOf(0f) }
                val openDistance = with(LocalDensity.current) { 40.dp.toPx() }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { navigator.player() }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { lift = 0f },
                                onDragEnd = { if (lift < -openDistance) navigator.player(); lift = 0f },
                                onDragCancel = { lift = 0f },
                                onVerticalDrag = { change, delta -> change.consume(); lift += delta },
                            )
                        }
                        .graphicsLayer { translationY = lift.coerceIn(-openDistance, 0f) * 0.5f }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(b, Modifier.size(44.dp), elevation = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b.author, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 1, color = Leather.Gilt)
                    }
                    IconButton(onClick = vm.player::seekBack) {
                        Icon(painterResource(R.drawable.ic_replay_15), stringResource(R.string.back_15))
                    }
                    IconButton(onClick = vm.player::togglePlayPause) {
                        if (state.isBuffering) CircularProgressIndicator(Modifier.size(24.dp), color = Leather.PaleGilt, strokeWidth = 2.dp)
                        else Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "Pause" else "Play", Modifier.size(32.dp))
                    }
                }
                HorizontalDivider(color = Leather.Gilt.copy(alpha = 0.3f))
            }
        }
    }
}
