package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookTile
import com.leatherbound.listening.ui.components.Ornament
import com.leatherbound.listening.ui.components.formatDuration
import com.leatherbound.listening.ui.components.ordinalSeries
import com.leatherbound.listening.ui.components.paper
import com.leatherbound.listening.ui.theme.LocalWideLayout

@Composable
fun AuthorScreen(vm: AppViewModel, nav: Navigator, author: String) {
    val books by remember(author) { vm.byAuthor(author) }.collectAsState(emptyList())
    // Group into series, with standalone books last.
    val groups = books.groupBy { it.seriesName }.toList().sortedBy { it.first == null }
    CollectionPage(nav, author, summary(books)) {
        groups.forEach { (series, list) -> section(series ?: if (groups.size > 1) "Standalone" else null, list) }
    }
}

@Composable
fun SeriesScreen(vm: AppViewModel, nav: Navigator, series: String) {
    val books by remember(series) { vm.bySeries(series) }.collectAsState(emptyList())
    val authors = books.map { it.author }.distinct().joinToString(", ")
    CollectionPage(nav, series, listOf(authors, summary(books)).filter { it.isNotBlank() }.joinToString(" · ")) {
        section(null, books, showIndex = true)
    }
}

/** A Plex/Jellyfin collection (or series folder), books in the collection's own order. */
@Composable
fun CollectionScreen(vm: AppViewModel, nav: Navigator, id: String) {
    val collection by remember(id) { vm.collection(id) }.collectAsState(null)
    val c = collection ?: return
    val authors = c.books.map { it.author }.distinct()
    val by = if (authors.size <= 3) authors.joinToString(", ") else "${authors.size} authors"
    CollectionPage(nav, c.collection.name, listOf(by, summary(c.books)).filter { it.isNotBlank() }.joinToString(" · ")) {
        section(null, c.books)
    }
}

/** Every book in one genre, by author then title. */
@Composable
fun GenreScreen(vm: AppViewModel, nav: Navigator, genre: String) {
    val books by remember(genre) { vm.genre(genre) }.collectAsState(emptyList())
    CollectionPage(nav, genre, summary(books)) { section(null, books) }
}

private fun summary(books: List<BookEntity>): String {
    val finished = books.count { it.isFinished }
    return "${books.size} ${if (books.size == 1) "book" else "books"} · ${formatDuration(books.sumOf { it.durationMs })}" +
        if (finished > 0) " · $finished finished" else ""
}

private class Sections {
    val items = mutableListOf<Triple<String?, List<BookEntity>, Boolean>>()
}

private fun Sections.section(title: String?, books: List<BookEntity>, showIndex: Boolean = false) {
    items += Triple(title, books, showIndex)
}

@Composable
private fun CollectionPage(nav: Navigator, title: String, subtitle: String, build: Sections.() -> Unit) {
    val sections = Sections().apply(build)
    val wide = LocalWideLayout.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 160.dp else 120.dp),
        contentPadding = PaddingValues(10.dp),
        modifier = Modifier.fillMaxSize().paper().statusBarsPadding(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 10.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        sections.items.forEach { (heading, books, showIndex) ->
            if (heading != null) item(span = { GridItemSpan(maxLineSpan) }) { Ornament(heading, Modifier.padding(10.dp)) }
            items(books, key = { it.id }) { book ->
                Column {
                    if (showIndex) ordinalSeries(book.seriesIndex)?.let {
                        Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 8.dp))
                    }
                    BookTile(book, { nav.book(book.id) })
                }
            }
        }
    }
}
