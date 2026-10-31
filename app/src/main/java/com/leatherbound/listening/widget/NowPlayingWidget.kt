package com.leatherbound.listening.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.leatherbound.listening.MainActivity
import com.leatherbound.listening.R
import com.leatherbound.listening.container
import com.leatherbound.listening.data.ArtworkProvider
import com.leatherbound.listening.playback.PlaybackService
import kotlinx.coroutines.launch

/** Home-screen widget: cover, title, and back 15 / play-pause / forward 15. */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        context.container.scope.launch {
            try {
                update(context, null, playing = false)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Redraws every widget. With no [bookId], shows the last book you played. */
        suspend fun update(context: Context, bookId: String?, playing: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NowPlayingWidget::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return
            val dao = context.container.repository.dao
            val book = bookId?.let { dao.book(it) } ?: dao.lastPlayed()

            val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
            views.setTextViewText(R.id.widget_title, book?.title ?: context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_author, book?.author ?: "Choose a book to begin")
            views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
            val cover = book?.let { cover(context, it) }
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, cover) else views.setImageViewResource(R.id.widget_cover, R.drawable.app_logo)

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_back, mediaButton(context, KeyEvent.KEYCODE_MEDIA_REWIND, foreground = false))
            views.setOnClickPendingIntent(R.id.widget_forward, mediaButton(context, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, foreground = false))
            views.setOnClickPendingIntent(
                R.id.widget_play,
                if (playing) mediaButton(context, KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false)
                else mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true),
            )
            manager.updateAppWidget(component, views)
        }

        private suspend fun cover(context: Context, book: com.leatherbound.listening.data.db.BookEntity): Bitmap? {
            val uri = ArtworkProvider.uri(book) ?: return null
            val request = ImageRequest.Builder(context).data(uri).size(256).allowHardware(false).build()
            return context.imageLoader.execute(request).drawable?.toBitmap(256, 256)
        }

        /**
         * Sends a media key to the playback service, which Media3 routes to the session (and, on
         * play with nothing loaded, resumes the last book). Play must start it as a foreground service.
         */
        private fun mediaButton(context: Context, keyCode: Int, foreground: Boolean): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, keyCode, intent, flags)
            } else {
                PendingIntent.getService(context, keyCode, intent, flags)
            }
        }
    }
}
