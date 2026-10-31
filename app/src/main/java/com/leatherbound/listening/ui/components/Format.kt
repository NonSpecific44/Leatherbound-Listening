package com.leatherbound.listening.ui.components

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    val h = minutes / 60; val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

fun ordinalSeries(index: Float?): String? = index?.let {
    if (it % 1f == 0f) "Book ${it.toInt()}" else "Book $it"
}
