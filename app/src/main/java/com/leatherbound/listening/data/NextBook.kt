package com.leatherbound.listening.data

import com.leatherbound.listening.data.db.BookEntity

/**
 * What comes after [current]: the next unfinished book in its series always wins. Only when
 * the series has nothing left (or the book isn't in one) does the top of Up Next get a turn.
 *
 * [series] is the whole series in reading order; [upNext] is the listening list in order.
 */
fun chooseNextBook(current: BookEntity, series: List<BookEntity>, upNext: List<BookEntity>): BookEntity? {
    val position = series.indexOfFirst { it.id == current.id }
    if (position >= 0) {
        series.drop(position + 1).firstOrNull { !it.isFinished }?.let { return it }
    }
    return upNext.firstOrNull { it.id != current.id && !it.isFinished }
}
