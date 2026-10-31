package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import com.leatherbound.listening.ui.components.DragHandle
import com.leatherbound.listening.ui.components.rememberSwipeDismissState
import com.leatherbound.listening.ui.components.swipeDismissContainer
import com.leatherbound.listening.ui.components.swipeDismissHandle
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Speed
import com.leatherbound.listening.playback.PlaybackService
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.R
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.data.db.ChapterEntity
import com.leatherbound.listening.playback.PlayerState
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookCover
import com.leatherbound.listening.ui.components.EmptyPage
import com.leatherbound.listening.ui.components.formatClock
import com.leatherbound.listening.ui.components.formatDuration
import com.leatherbound.listening.ui.components.giltFrame
import com.leatherbound.listening.ui.components.paper
import com.leatherbound.listening.ui.theme.Leather
import com.leatherbound.listening.ui.theme.LocalWideLayout
import kotlinx.coroutines.flow.flowOf

private val speeds = listOf(0.75f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f, 2.5f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: AppViewModel, nav: Navigator) {
    val state by vm.playerState.collectAsStateWithLifecycle()
    val bookId = state.bookId
    val book by remember(bookId) { bookId?.let { vm.book(it) } ?: flowOf(null) }.collectAsState(null)
    val chapters by remember(bookId) { bookId?.let { vm.chapters(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    var showContents by remember { mutableStateOf(false) }
    val bookmarks by remember(bookId) { bookId?.let { vm.bookmarks(it) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val b = book
    val dismiss = rememberSwipeDismissState { nav.back() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().swipeDismissContainer(dismiss)) {
        if (b == null) {
            Column(Modifier.statusBarsPadding()) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                EmptyPage("Nothing playing", "Choose a book from your library to begin.")
            }
            return@Surface
        }
        val chapter = chapters.lastOrNull { it.startMs <= state.positionMs }
        val onChapter: (ChapterEntity) -> Unit = { vm.player.seekTo(b.id, it.startMs); showContents = false }

        if (LocalWideLayout.current) {
            // Two-page spread: left page is the player, right page the table of contents.
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                Page(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().swipeDismissHandle(dismiss)) {
                        DragHandle()
                        IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                    }
                    PlayerPage(vm, b, chapters, chapter, state, Modifier.fillMaxWidth())
                }
                Box(
                    Modifier.width(18.dp).fillMaxHeight().background(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.04f)))
                    )
                )
                Page(Modifier.weight(1f)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp)) {
                        Contents(chapters, state.positionMs, true, onChapter)
                        Bookmarks(bookmarks, { vm.player.seekTo(b.id, it) }, vm::deleteBookmark)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().paper().statusBarsPadding().navigationBarsPadding()) {
                DragHandle(Modifier.swipeDismissHandle(dismiss))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.swipeDismissHandle(dismiss)) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                    Text("Now listening", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showContents = true }, enabled = chapters.isNotEmpty() || bookmarks.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.List, "Contents and bookmarks")
                    }
                }
                PlayerPage(vm, b, chapters, chapter, state, Modifier.weight(1f))
            }
        }

        if (showContents) {
            ModalBottomSheet(onDismissRequest = { showContents = false }, containerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                    Bookmarks(bookmarks, { vm.player.seekTo(b.id, it); showContents = false }, vm::deleteBookmark)
                    Contents(chapters, state.positionMs, true, onChapter)
                }
            }
        }
    }
}

@Composable
private fun Page(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxHeight(),
    ) { Column(Modifier.paper()) { content() } }
}

@Composable
private fun PlayerPage(
    vm: AppViewModel, b: BookEntity, chapters: List<ChapterEntity>, chapter: ChapterEntity?, state: PlayerState, modifier: Modifier,
) {
    val pos = state.positionMs
    // Slider tracks the current chapter, which is what you care about mid-book.
    val start = chapter?.startMs ?: 0L
    val end = (chapter?.endMs ?: state.durationMs).coerceAtLeast(start + 1)
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var bookmarkDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Brief label over the cover confirming a gesture ("−15 s", "Chapter 4").
    var flash by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(flash) { if (flash != null) { delay(700); flash = null } }
    fun show(text: String) { flash = text to ((flash?.second ?: 0) + 1) }

    fun previousChapter() {
        val target = if (chapter != null && pos - chapter.startMs > 3000) chapter
        else chapters.getOrNull((chapter?.index ?: 0) - 1)
        vm.player.seekTo(b.id, target?.startMs ?: 0L)
        show(target?.let { "Chapter ${it.index + 1}" } ?: "Beginning")
    }
    fun nextChapter() {
        val next = chapters.getOrNull((chapter?.index ?: -1) + 1) ?: return
        vm.player.seekTo(b.id, next.startMs)
        show("Chapter ${next.index + 1}")
    }

    Column(
        modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Spacer(Modifier.height(8.dp))
        var swipe by remember { mutableFloatStateOf(0f) }
        // Gesture handlers outlive recompositions; these always call the latest versions.
        val onPrevious by rememberUpdatedState { previousChapter() }
        val onNext by rememberUpdatedState { nextChapter() }
        val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
        Box(
            Modifier.widthIn(max = 340.dp).fillMaxWidth(0.82f).giltFrame(inset = (-10).dp, notch = 14.dp).padding(4.dp)
                .graphicsLayer { translationX = swipe * 0.35f; rotationZ = swipe / 120f }
                // Double-tap the left or right half to skip; long-press to bookmark.
                .pointerInput(b.id) {
                    detectTapGestures(
                        onDoubleTap = { o ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (o.x < size.width / 2) { vm.player.seekBack(); show("−15 s") } else { vm.player.seekForward(); show("+15 s") }
                        },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            bookmarkDialog = true
                        },
                    )
                }
                // Swipe sideways to turn chapters, like turning a page.
                .pointerInput(b.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                swipe > swipeThreshold -> { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onPrevious() }
                                swipe < -swipeThreshold -> { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onNext() }
                            }
                            swipe = 0f
                        },
                        onDragCancel = { swipe = 0f },
                        onHorizontalDrag = { change, delta -> change.consume(); swipe += delta },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            BookCover(b, Modifier.fillMaxWidth(), elevation = 16.dp)
            androidx.compose.animation.AnimatedVisibility(flash != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    flash?.first.orEmpty(),
                    style = MaterialTheme.typography.titleLarge, color = Leather.PaleGilt,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(b.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(b.author, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chapter != null && chapters.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Chapter ${chapter.index + 1} of ${chapters.size} · ${chapter.title}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))

        val fraction = dragging ?: ((pos - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { vm.player.seekTo(b.id, start + ((end - start) * it).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
        val shown = start + ((end - start) * fraction).toLong()
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(shown - start), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text("-" + formatClock(end - shown), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "${formatDuration(state.durationMs - pos)} left in book · ${if (state.durationMs > 0) pos * 100 / state.durationMs else 0}%",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = ::previousChapter) { Icon(Icons.Filled.SkipPrevious, "Previous chapter", Modifier.size(30.dp)) }
            IconButton(onClick = vm.player::seekBack, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_replay_15), "Back 15 seconds", Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = vm.player::togglePlayPause,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Leather.Oxblood, contentColor = Leather.PaleGilt),
                modifier = Modifier.size(80.dp).border(1.5.dp, Leather.Gilt, CircleShape).padding(4.dp),
            ) {
                if (state.isBuffering) CircularProgressIndicator(color = Leather.PaleGilt, modifier = Modifier.size(36.dp))
                else Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.isPlaying) "Pause" else "Play", Modifier.size(44.dp))
            }
            IconButton(onClick = vm.player::seekForward, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_forward_15), "Forward 15 seconds", Modifier.size(36.dp))
            }
            IconButton(
                onClick = ::nextChapter,
                enabled = chapter == null || chapter.index < chapters.lastIndex,
            ) { Icon(Icons.Filled.SkipNext, "Next chapter", Modifier.size(30.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AssistChip(onClick = { speedMenu = true }, label = { Text("${"%.2f".format(state.speed).trimEnd('0').trimEnd('.')}×") },
                    leadingIcon = { Icon(Icons.Filled.Speed, null, Modifier.size(18.dp)) })
                DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
                    Text("Speed for this book", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    speeds.forEach { s ->
                        DropdownMenuItem(text = { Text("${s}×") }, onClick = { vm.player.setSpeed(s); speedMenu = false })
                    }
                }
            }
            Box {
                val sleepLabel = when {
                    state.sleepAtChapterEnd -> "End of chapter"
                    state.sleepLeftMs != null -> formatClock(state.sleepLeftMs)
                    else -> "Sleep"
                }
                AssistChip(onClick = { sleepMenu = true }, label = { Text(sleepLabel) },
                    leadingIcon = { Icon(Icons.Filled.Bedtime, null, Modifier.size(18.dp)) })
                DropdownMenu(sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    Text("Shake the phone to add 10 minutes", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                        DropdownMenuItem(text = { Text("$m minutes") }, onClick = { vm.setSleepTimer(m); sleepMenu = false })
                    }
                    DropdownMenuItem(text = { Text("End of chapter") }, onClick = { vm.setSleepTimer(PlaybackService.SLEEP_END_OF_CHAPTER); sleepMenu = false })
                    if (state.sleepLeftMs != null || state.sleepAtChapterEnd) {
                        DropdownMenuItem(text = { Text("Turn off") }, onClick = { vm.setSleepTimer(0); sleepMenu = false })
                    }
                }
            }
            AssistChip(onClick = { bookmarkDialog = true }, label = { Text("Bookmark") },
                leadingIcon = { Icon(Icons.Filled.BookmarkAdd, null, Modifier.size(18.dp)) })
        }
        if (bookmarkDialog) BookmarkDialog(formatClock(pos), onDismiss = { bookmarkDialog = false }) { note ->
            vm.addBookmark(note)
            bookmarkDialog = false
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BookmarkDialog(at: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmark at $at") },
        text = { OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, minLines = 2) },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
