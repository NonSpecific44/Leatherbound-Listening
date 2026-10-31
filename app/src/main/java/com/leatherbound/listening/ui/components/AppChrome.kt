package com.leatherbound.listening.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leatherbound.listening.R
import com.leatherbound.listening.data.ServerStatus
import com.leatherbound.listening.data.db.SourceEntity
import com.leatherbound.listening.ui.CollectionView
import com.leatherbound.listening.ui.theme.Leather

/** The bar across the top of every page: logo on the left, downloads on the right. */
@Composable
fun AppTopBar(activeDownloads: Int, downloadProgress: Float?, onDownloads: () -> Unit, onLogo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onLogo).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(R.drawable.app_logo), contentDescription = "Home", modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Leatherbound", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                DownloadsButton(activeDownloads, downloadProgress, onDownloads)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f))
        }
    }
}

/**
 * Downloads icon. While something is downloading, the arrow bobs, a ring shows overall
 * progress, and a badge counts the books in the queue.
 */
@Composable
fun DownloadsButton(active: Int, progress: Float?, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "downloads")
    val bob by transition.animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        BadgedBox(badge = { if (active > 0) Badge(containerColor = Leather.Gilt, contentColor = Leather.DarkLeather) { Text("$active") } }) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                if (active > 0) {
                    if (progress != null) {
                        CircularProgressIndicator(
                            progress = { progress }, modifier = Modifier.fillMaxSize(), strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                Icon(
                    if (active > 0) Icons.Filled.Download else Icons.Filled.DownloadDone,
                    contentDescription = if (active > 0) "Downloads, $active in progress" else "Downloads",
                    modifier = Modifier.size(22.dp).graphicsLayer { if (active > 0) translationY = bob * 3.dp.toPx() },
                )
            }
        }
    }
}

/** One chip per library showing whether its server is reachable. Tap to check again. */
@Composable
fun ServerStatusRow(sources: List<SourceEntity>, status: Map<Long, ServerStatus>, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha",
    )
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
        items(sources, key = { it.id }) { source ->
            val state = status[source.id] ?: ServerStatus.CHECKING
            val (dot, label) = when (state) {
                ServerStatus.ONLINE -> Color(0xFF5B8C51) to "Online"
                ServerStatus.OFFLINE -> MaterialTheme.colorScheme.error to "Offline"
                ServerStatus.CHECKING -> MaterialTheme.colorScheme.outline to "Checking…"
                ServerStatus.ON_DEVICE -> Leather.Gilt to "On this phone"
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onRefresh).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(9.dp).graphicsLayer { alpha = if (state == ServerStatus.CHECKING) pulse else 1f }
                        .background(dot, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(source.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Text(" · $label", style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/** A collection as a little stack: up to four covers in a gilt frame, with its name. */
@Composable
fun CollectionCard(collection: CollectionView, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp) {
    // Dp.Unspecified fills the grid cell instead of a fixed width.
    Column(modifier.then(if (width != Dp.Unspecified) Modifier.width(width) else Modifier.fillMaxWidth()).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                .background(Leather.Oxblood).giltFrame(inset = 4.dp, notch = 8.dp).padding(10.dp)
        ) {
            val covers = collection.books.take(4)
            if (covers.size < 4) {
                BookCover(covers.first(), Modifier.fillMaxSize(), elevation = 4.dp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    covers.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            row.forEach { BookCover(it, Modifier.weight(1f), elevation = 2.dp) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(collection.collection.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${collection.books.size} ${if (collection.books.size == 1) "book" else "books"}",
            style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Are you sure?" for anything that deletes. */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
