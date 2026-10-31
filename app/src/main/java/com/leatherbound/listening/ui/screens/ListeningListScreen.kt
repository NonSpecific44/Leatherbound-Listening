package com.leatherbound.listening.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leatherbound.listening.ui.AppViewModel
import com.leatherbound.listening.ui.Navigator
import com.leatherbound.listening.ui.components.BookRow
import com.leatherbound.listening.ui.components.EmptyPage
import com.leatherbound.listening.ui.components.paper

/** The listening list: books queued to read next, in the order you choose. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListeningListScreen(vm: AppViewModel, nav: Navigator) {
    val list by vm.listeningList.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().paper().statusBarsPadding()) {
        Text("Up Next", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 20.dp, top = 20.dp))
        Text(
            if (settings.autoQueueSeries) "When a book ends, the next in its series plays — otherwise the top of this list."
            else "Your reading list, in order.",
            style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (list.isEmpty()) {
            EmptyPage("The list is empty", "Tap “Up next” on any book to add it here.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(list, key = { _, b -> b.id }) { i, book ->
                // Swipe left to take a book off the list.
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
                    if (it == SwipeToDismissBoxValue.EndToStart) vm.toggleList(book.id)
                    it == SwipeToDismissBoxValue.EndToStart
                })
                SwipeToDismissBox(
                    state = swipe,
                    enableDismissFromStartToEnd = false,
                    modifier = Modifier.animateItem(),
                    backgroundContent = {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Remove", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
                                Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    },
                ) {
                BookRow(book, { nav.book(book.id) }, Modifier.background(MaterialTheme.colorScheme.background).paper()) {
                    Row {
                        IconButton(onClick = { vm.play(book.id) }) { Icon(Icons.Filled.PlayArrow, "Play") }
                        Column {
                            IconButton(onClick = { vm.moveInList(book.id, up = true) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { vm.moveInList(book.id, up = false) }, enabled = i < list.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        }
                        IconButton(onClick = { vm.toggleList(book.id) }) { Icon(Icons.Filled.Close, "Remove from list") }
                    }
                }
                }
            }
        }
    }
}
