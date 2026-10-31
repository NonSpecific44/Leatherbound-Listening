package com.leatherbound.listening.watch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Matches the phone app's WearProtocol. */
private const val NOW_PLAYING = "/leatherbound/now_playing"
private const val COMMAND = "/leatherbound/command/"

private val Gilt = Color(0xFFC9A45C)
private val Parchment = Color(0xFFEADBC0)
private val Hide = Color(0xFF2E211A)

private val LeatherColors = Colors(
    primary = Gilt, primaryVariant = Color(0xFF9A7432), secondary = Color(0xFFD08A5E),
    background = Color.Black, surface = Hide, onPrimary = Color(0xFF2A1A10),
    onSurface = Parchment, onSurfaceVariant = Color(0xFFCDB898), onBackground = Parchment,
)

/** What the phone says is playing. The position is as of [updatedAt]; the watch runs the clock on from there. */
data class NowPlaying(
    val title: String,
    val author: String,
    val chapter: String,
    val chapterStartMs: Long,
    val chapterEndMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val speed: Float,
    val updatedAt: Long,
    val cover: Bitmap?,
) {
    fun positionAt(now: Long): Long =
        if (playing) positionMs + ((now - updatedAt).coerceAtLeast(0) * speed).toLong() else positionMs
}

class WatchActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private var nowPlaying by mutableStateOf<NowPlaying?>(null)
    private var loaded by mutableStateOf(false)
    private val dataClient by lazy { Wearable.getDataClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colors = LeatherColors) {
                Scaffold(timeText = { TimeText() }) {
                    NowPlayingScreen(nowPlaying, loaded, ::send) { playing ->
                        // Show the change straight away; the phone's update follows.
                        nowPlaying = nowPlaying?.let { it.copy(playing = playing, positionMs = it.positionAt(System.currentTimeMillis()), updatedAt = System.currentTimeMillis()) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dataClient.addListener(this)
        lifecycleScope.launch {
            runCatching {
                val items = dataClient.getDataItems(Uri.parse("wear://*$NOW_PLAYING")).await()
                items.use { buffer -> buffer.firstOrNull()?.let { nowPlaying = read(DataMapItem.fromDataItem(it).dataMap) } }
            }
            loaded = true
        }
    }

    override fun onPause() {
        super.onPause()
        dataClient.removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val map = events.use { buffer ->
            buffer.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == NOW_PLAYING }
                .map { DataMapItem.fromDataItem(it.dataItem).dataMap }.lastOrNull()
        } ?: return
        lifecycleScope.launch { nowPlaying = read(map) }
    }

    private suspend fun read(map: DataMap): NowPlaying? {
        if (map.getString("bookId").isNullOrEmpty()) return null
        return NowPlaying(
            title = map.getString("title").orEmpty(),
            author = map.getString("author").orEmpty(),
            chapter = map.getString("chapter").orEmpty(),
            chapterStartMs = map.getLong("chapterStartMs"),
            chapterEndMs = map.getLong("chapterEndMs"),
            positionMs = map.getLong("positionMs"),
            playing = map.getBoolean("playing"),
            speed = map.getFloat("speed", 1f),
            updatedAt = map.getLong("updatedAt"),
            cover = map.getAsset("cover")?.let { bitmap(it) },
        )
    }

    private suspend fun bitmap(asset: Asset): Bitmap? = runCatching {
        dataClient.getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** Sends a command to every connected phone (in practice, the one this watch is paired with). */
    private fun send(command: String) {
        lifecycleScope.launch {
            runCatching {
                val nodes = Wearable.getNodeClient(this@WatchActivity).connectedNodes.await()
                val messages = Wearable.getMessageClient(this@WatchActivity)
                nodes.forEach { messages.sendMessage(it.id, COMMAND + command, ByteArray(0)).await() }
            }
        }
    }
}

@Composable
private fun NowPlayingScreen(state: NowPlaying?, loaded: Boolean, send: (String) -> Unit, onToggle: (Boolean) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state?.playing) {
        while (state?.playing == true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (state == null) {
            Text(
                if (loaded) "Start a book in Leatherbound Listening on your phone" else "Connecting…",
                textAlign = TextAlign.Center, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(24.dp),
            )
            if (loaded) Button(onClick = { send("toggle") }, modifier = Modifier.padding(top = 110.dp).size(ButtonDefaults.SmallButtonSize)) {
                Icon(Icons.Filled.PlayArrow, "Resume the last book")
            }
            return@Box
        }

        // The ring is how far through the chapter you are.
        val position = state.positionAt(now)
        val chapterLength = (state.chapterEndMs - state.chapterStartMs).coerceAtLeast(1)
        CircularProgressIndicator(
            progress = ((position - state.chapterStartMs).toFloat() / chapterLength).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize().padding(2.dp),
            startAngle = 300f, endAngle = 240f,
            indicatorColor = Gilt, trackColor = Hide, strokeWidth = 4.dp,
        )

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(18.dp))
            state.cover?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(4.dp))
            }
            Text(
                state.title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.title3,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(
                state.chapter.ifBlank { state.author } + " · " + remaining(state.chapterEndMs - position),
                fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { send("back") }, colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                ) { Icon(Icons.Filled.FastRewind, "Back 15 seconds") }
                Button(
                    onClick = { onToggle(!state.playing); send("toggle") },
                    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                ) { Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play") }
                Button(
                    onClick = { send("forward") }, colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                ) { Icon(Icons.Filled.FastForward, "Forward 15 seconds") }
            }
        }
    }
}

/** "12 min left" in the chapter. */
private fun remaining(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min left"
        minutes >= 1 -> "$minutes min left"
        else -> "under a minute left"
    }
}
