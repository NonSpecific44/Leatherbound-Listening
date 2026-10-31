package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.ui.unit.Dp
import com.leatherbound.listening.data.db.BookEntity
import com.leatherbound.listening.ui.CollectionView
import com.leatherbound.listening.ui.LibraryView
import com.leatherbound.listening.ui.components.CollectionCard
import com.leatherbound.listening.ui.components.Ornament
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.data.LibrarySort
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.LibraryFilter
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookRow
import com.leatherbound.listening.ui.components.BookTile
import com.leatherbound.listening.ui.components.EmptyPage
import com.leatherbound.listening.ui.components.paper
import com.leatherbound.listening.ui.theme.LocalWideLayout

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel, nav: Navigator) {
    val books by vm.library.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val error by vm.syncError.collectAsStateWithLifecycle()
    val view by vm.libraryView.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val matching by vm.matchingCollections.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    val wide = LocalWideLayout.current
    val showingBooks = view == LibraryView.BOOKS

    Column(Modifier.fillMaxSize().paper().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (showingBooks) Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            trailingIcon = {
                                if (sort == settings.librarySort) Icon(if (settings.sortDescending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null)
                            },
                            onClick = { vm.setSort(sort); sortMenu = false },
                        )
                    }
                }
            }
            if (showingBooks) IconButton(onClick = { vm.setGrid(!settings.libraryGrid) }) {
                Icon(if (settings.libraryGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, "Change layout")
            }
            if (syncing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.sync() }) { Icon(Icons.Filled.Refresh, "Sync libraries") }
        }

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = showingBooks, onClick = { vm.libraryView.value = LibraryView.BOOKS }, label = { Text("Books") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp)) })
            FilterChip(selected = !showingBooks, onClick = { vm.libraryView.value = LibraryView.COLLECTIONS }, label = { Text("Collections (${collections.size})") },
                leadingIcon = { Icon(Icons.Filled.CollectionsBookmark, null, Modifier.size(18.dp)) })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = { Text(if (showingBooks) "Search titles, authors, series, genres, collections" else "Search collections") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Clear") } },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (showingBooks) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { f ->
                FilterChip(selected = f == filter, onClick = { vm.filter.value = f }, label = { Text(f.label) })
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        if (!showingBooks) {
            CollectionsGrid(if (query.isBlank()) collections else matching, query, nav)
            return@Column
        }
        Text(
            "${books.size} ${if (books.size == 1) "volume" else "volumes"} · sorted by ${settings.librarySort.label.lowercase()}" +
                if (matching.isNotEmpty()) " · ${matching.size} ${if (matching.size == 1) "collection" else "collections"}" else "",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        // Sorting by genre shows a heading above each genre.
        val sections: List<Pair<String?, List<BookEntity>>> =
            if (settings.librarySort == LibrarySort.GENRE) books.groupBy { it.primaryGenre ?: "No genre" }.toList() else listOf(null to books)

        // Pull down to sync with your servers.
        PullToRefreshBox(isRefreshing = syncing, onRefresh = { vm.sync() }, modifier = Modifier.fillMaxSize()) {
        when {
            books.isEmpty() && matching.isEmpty() -> EmptyPage("Nothing on these shelves", if (query.isNotBlank() || filter != LibraryFilter.ALL) "Try a different search or filter." else "Add a library in Settings to begin.")
            settings.libraryGrid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 160.dp else 120.dp),
                contentPadding = PaddingValues(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (matching.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "collections") { MatchingCollections(matching, nav) }
                sections.forEach { (heading, list) ->
                    if (heading != null) item(span = { GridItemSpan(maxLineSpan) }, key = "genre:$heading") { GenreHeading(heading, list.size) }
                    items(list, key = { it.id }) { BookTile(it, { nav.book(it.id) }) }
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (matching.isNotEmpty()) item(key = "collections") { MatchingCollections(matching, nav) }
                sections.forEach { (heading, list) ->
                    if (heading != null) item(key = "genre:$heading") { GenreHeading(heading, list.size) }
                    items(list, key = { it.id }) { BookRow(it, { nav.book(it.id) }) }
                }
            }
        }
        }
    }
}

@Composable
private fun GenreHeading(genre: String, count: Int) {
    Ornament("$genre  ·  $count", Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
}

/** Collections that match the search, shown above the matching books. */
@Composable
private fun MatchingCollections(matching: List<CollectionView>, nav: Navigator) {
    Column {
        Ornament("Collections", Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
        LazyRow { items(matching, key = { it.collection.id }) { CollectionCard(it, { nav.collection(it.collection.id) }) } }
    }
}

@Composable
private fun CollectionsGrid(collections: List<CollectionView>, query: String, nav: Navigator) {
    if (collections.isEmpty()) {
        EmptyPage(
            if (query.isBlank()) "No collections yet" else "No collections match",
            if (query.isBlank()) "Collections from Plex and Jellyfin, and series folders on this phone, appear here after a sync." else "Try a different search.",
        )
        return
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxSize()) {
        items(collections, key = { it.collection.id }) { CollectionCard(it, { nav.collection(it.collection.id) }, width = Dp.Unspecified) }
    }
}
