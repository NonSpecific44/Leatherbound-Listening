package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import com.leatherbound.listening.ui.components.Shelf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.components.BookCover
import com.leatherbound.listening.ui.components.Ornament
import com.leatherbound.listening.ui.components.formatDuration
import com.leatherbound.listening.ui.components.paper
import com.leatherbound.listening.ui.theme.LocalWideLayout
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(vm: AppViewModel, nav: com.leatherbound.listening.ui.Navigator) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wide = LocalWideLayout.current
    var editGoal by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().paper().verticalScroll(rememberScrollState()).statusBarsPadding().padding(16.dp)) {
        Text("Your reading ledger", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp))
        Spacer(Modifier.height(12.dp))
        GoalCard(stats.finishedThisYearBooks.size, settings.yearlyGoal) { editGoal = true }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = if (wide) 6 else 2) {
            val tile = Modifier.weight(1f)
            StatTile("Today", formatDuration(stats.todayMs), tile)
            StatTile("This week", formatDuration(stats.weekMs), tile)
            StatTile("All time", formatDuration(stats.totalMs), tile)
            StatTile("Daily average", formatDuration(stats.dailyAverageMs), tile, "last 30 days")
            StatTile("Streak", "${stats.streakDays} ${if (stats.streakDays == 1) "day" else "days"}", tile)
            StatTile("Books finished", stats.finishedTotal.toString(), tile, "${stats.finishedThisYear} this year")
        }

        Spacer(Modifier.height(20.dp))
        Ornament("Last fortnight")
        DailyChart(stats.last14Days)

        if (stats.finishedThisYearBooks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Shelf("Finished in ${java.time.LocalDate.now().year}", stats.finishedThisYearBooks, { nav.book(it.id) }, tileWidth = 110.dp)
        }

        if (stats.topBooks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Ornament("Most listened this month")
            stats.topBooks.forEach { (book, ms) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(44.dp), elevation = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(book.author, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                    }
                    Text(formatDuration(ms), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (stats.topAuthors.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Ornament("Favourite authors")
            stats.topAuthors.forEachIndexed { i, (author, ms) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(32.dp))
                    Text(author, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(formatDuration(ms), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (editGoal) GoalDialog(settings.yearlyGoal, onDismiss = { editGoal = false }) { vm.setYearlyGoal(it); editGoal = false }
}

/** "7 of 12 books this year", drawn as a gilt ring. */
@Composable
private fun GoalCard(done: Int, goal: Int, onEdit: () -> Unit) {
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Card(onClick = onEdit, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center, modifier = Modifier.size(72.dp)) {
                Canvas(Modifier.size(72.dp)) {
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawArc(track, 0f, 360f, false, style = stroke)
                    drawArc(ring, -90f, 360f * (done.toFloat() / goal).coerceIn(0f, 1f), false, style = stroke)
                }
                Text("$done", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${java.time.LocalDate.now().year} reading goal".uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text("$done of $goal books", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        done >= goal -> "Goal reached. Bravo."
                        else -> "${goal - done} to go · tap to change"
                    },
                    style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GoalDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Books this year") },
        text = {
            Column {
                Text("${value.toInt()} books", style = MaterialTheme.typography.headlineSmall)
                Slider(value = value, onValueChange = { value = it }, valueRange = 1f..100f)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.toInt()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, note: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DailyChart(days: List<Pair<java.time.LocalDate, Long>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(30 * 60_000L)
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val slot = size.width / days.size.coerceAtLeast(1)
            val w = slot * 0.6f
            days.forEachIndexed { i, (_, ms) ->
                val h = (ms.toFloat() / max * size.height).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (ms > 0) bar else empty,
                    topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEach { (date, _) ->
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text("Peak day: ${formatDuration(days.maxOfOrNull { it.second } ?: 0L)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
