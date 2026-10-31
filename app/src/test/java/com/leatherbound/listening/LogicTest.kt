package com.leatherbound.listening

import com.leatherbound.listening.data.authorSortKey
import com.leatherbound.listening.data.db.TrackEntity
import com.leatherbound.listening.data.local.LocalScanner
import com.leatherbound.listening.data.local.NaturalOrder
import com.leatherbound.listening.data.remote.PlexClient
import com.leatherbound.listening.data.remote.PlexConnection
import com.leatherbound.listening.data.remote.TrackProgress
import com.leatherbound.listening.data.remote.bookProgress
import com.leatherbound.listening.data.remote.RemoteCollection
import com.leatherbound.listening.data.remote.firstName
import com.leatherbound.listening.data.remote.seriesIndex
import com.leatherbound.listening.playback.Rewind
import com.leatherbound.listening.playback.locate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test fun naturalOrderSortsNumbersByValue() {
        val files = listOf("Part 10.mp3", "Part 2.mp3", "Part 1.mp3", "part 3.mp3")
        assertEquals(listOf("Part 1.mp3", "Part 2.mp3", "part 3.mp3", "Part 10.mp3"), files.sortedWith(NaturalOrder))
    }

    @Test fun smartRewindScalesWithTimeAway() {
        assertEquals(2_000L, Rewind.forPause(5_000, 10_000, smart = true))
        assertEquals(5_000L, Rewind.forPause(5_000, 10 * 60_000, smart = true))
        assertEquals(15_000L, Rewind.forPause(5_000, 3 * 60 * 60_000, smart = true))
        assertEquals(30_000L, Rewind.forPause(5_000, 3L * 24 * 60 * 60_000, smart = true))
        assertEquals(5_000L, Rewind.forPause(5_000, 3L * 24 * 60 * 60_000, smart = false))
        assertEquals(5_000L, Rewind.forPause(5_000, -1, smart = true))
    }

    @Test fun rewindOfZeroStaysOff() {
        assertEquals(0L, Rewind.forPause(0, 3L * 24 * 60 * 60_000, smart = true))
    }

    private fun track(i: Int, start: Long, dur: Long) = TrackEntity("b", i, "t$i", "r$i", "u$i", dur, start)

    @Test fun locateFindsFileAndOffset() {
        val tracks = listOf(track(0, 0, 1000), track(1, 1000, 2000), track(2, 3000, 500))
        assertEquals(0 to 0L, tracks.locate(0))
        assertEquals(0 to 999L, tracks.locate(999))
        assertEquals(1 to 0L, tracks.locate(1000))
        assertEquals(2 to 200L, tracks.locate(3200))
        assertEquals(0 to 0L, emptyList<TrackEntity>().locate(500))
    }

    @Test fun authorsSortBySurname() {
        assertEquals("le guin ursula k.", authorSortKey("Ursula K. Le Guin"))
        assertEquals("tolkien j.r.r.", authorSortKey("J.R.R. Tolkien"))
        assertEquals("homer", authorSortKey("Homer"))
    }

    @Test fun seriesNumberFromFolderName() {
        assertEquals(1f, LocalScanner.leadingNumber("01 - The Hobbit"))
        assertEquals(3f, LocalScanner.leadingNumber("Book 3 - Prisoner"))
        assertEquals(2.5f, LocalScanner.leadingNumber("#2.5 Novella"))
        assertNull(LocalScanner.leadingNumber("The Hobbit"))
    }

    @Test fun serverProgressUsesMostRecentlyPlayedFile() {
        val p = bookProgress(
            listOf(
                TrackProgress(1000, null, played = true, lastPlayedAt = 100),
                TrackProgress(2000, 500, played = false, lastPlayedAt = 200),
                TrackProgress(3000, null, played = false, lastPlayedAt = 0),
            )
        )
        assertEquals(1500L, p.positionMs)
        assertEquals(200L, p.updatedAt)
        assertFalse(p.finished)
    }

    @Test fun serverProgressPlayedThroughLandsAtEndOfFile() {
        val p = bookProgress(listOf(TrackProgress(1000, null, true, 50), TrackProgress(2000, null, true, 60)))
        assertEquals(3000L, p.positionMs)
        assertTrue(p.finished)
    }

    @Test fun serverProgressNeverPlayed() {
        val p = bookProgress(listOf(TrackProgress(1000, null, false, 0)))
        assertNull(p.positionMs)
        assertEquals(0L, p.updatedAt)
    }

    @Test fun plexPrefersLocalThenPlainLocalThenRemoteThenRelay() {
        val order = PlexClient("test").candidates(
            listOf(
                PlexConnection("https://relay.plex.direct:8443", local = false, relay = true),
                PlexConnection("https://1-2-3-4.abc.plex.direct:32400", local = false, address = "1.2.3.4"),
                PlexConnection("https://192-168-1-5.abc.plex.direct:32400/", local = true, address = "192.168.1.5"),
                PlexConnection("https://[fe80--1].abc.plex.direct:32400", local = true, address = "fe80::1", ipv6 = true),
            )
        )
        assertEquals(
            listOf(
                "https://192-168-1-5.abc.plex.direct:32400",
                "https://[fe80--1].abc.plex.direct:32400",
                "http://192.168.1.5:32400",
                "https://1-2-3-4.abc.plex.direct:32400",
                "https://relay.plex.direct:8443",
            ),
            order,
        )
    }

    @Test fun seriesIsTheSmallestCollectionABookIsIn() {
        val map = seriesIndex(
            listOf(
                RemoteCollection("2", "Favourites", listOf("x", "c", "d", "y", "z")),
                RemoteCollection("1", "Discworld", listOf("a", "b", "c")),
            )
        )
        assertEquals("Discworld" to 3f, map["c"])
        assertEquals("Favourites" to 3f, map["d"])
        assertNull(map["q"])
    }

    private fun book(id: String, finished: Boolean = false) =
        com.leatherbound.listening.data.db.BookEntity(id, 1, id, id, id, "Author", isFinished = finished)

    @Test fun nextBookPrefersTheSeries() {
        val series = listOf(book("s1"), book("s2"), book("s3"))
        val upNext = listOf(book("u1"))
        assertEquals("s2", com.leatherbound.listening.data.chooseNextBook(series[0], series, upNext)?.id)
    }

    @Test fun nextBookSkipsFinishedSeriesBooks() {
        val series = listOf(book("s1"), book("s2", finished = true), book("s3"))
        assertEquals("s3", com.leatherbound.listening.data.chooseNextBook(series[0], series, listOf(book("u1")))?.id)
    }

    @Test fun upNextOnlyWhenTheSeriesHasNothingLeft() {
        val series = listOf(book("s1"), book("s2"))
        val upNext = listOf(book("s2"), book("u1"))
        // Last in the series: Up Next's top book, skipping the one just finished.
        assertEquals("u1", com.leatherbound.listening.data.chooseNextBook(series[1], series, upNext)?.id)
        // Not in a series at all.
        assertEquals("u1", com.leatherbound.listening.data.chooseNextBook(book("solo"), emptyList(), listOf(book("u1")))?.id)
        assertNull(com.leatherbound.listening.data.chooseNextBook(book("solo"), emptyList(), emptyList()))
    }

    @Test fun homeLayoutRoundTripsAndFillsInNewSections() {
        val parsed = com.leatherbound.listening.data.HomeLayout.parse("COLLECTIONS,-UP_NEXT,BOGUS")
        assertEquals(com.leatherbound.listening.data.HomeSection.COLLECTIONS, parsed.order.first())
        assertTrue(com.leatherbound.listening.data.HomeSection.UP_NEXT in parsed.hidden)
        assertEquals(com.leatherbound.listening.data.HomeSection.entries.size, parsed.order.size)
        assertEquals(parsed, com.leatherbound.listening.data.HomeLayout.parse(parsed.encode()))
        assertEquals(com.leatherbound.listening.data.HomeLayout.DEFAULT, com.leatherbound.listening.data.HomeLayout.parse(null))
        assertEquals(
            listOf(com.leatherbound.listening.data.HomeSection.UP_NEXT, com.leatherbound.listening.data.HomeSection.JUMP_BACK_IN),
            com.leatherbound.listening.data.HomeLayout.DEFAULT.order.take(2),
        )
    }

    @Test fun genresGroupBooksUnderEachGenre() {
        fun b(id: String, genres: String?) = com.leatherbound.listening.data.db.BookEntity(id, 1, id, id, id, "A", genres = genres)
        val groups = com.leatherbound.listening.data.groupByGenre(listOf(b("1", "Fantasy, Humour"), b("2", "fantasy"), b("3", null), b("4", "Humour"), b("5", "Fantasy")))
        assertEquals(listOf("Fantasy", "Humour", "No genre"), groups.map { it.first })
        assertEquals(listOf("1", "2", "5"), groups.first().second.map { it.id })
        assertEquals(listOf("1", "4"), groups.first { it.first == "Humour" }.second.map { it.id })
    }

    @Test fun greetingUsesFirstName() {
        assertEquals("Fergus", firstName("Fergus Eaton"))
        assertEquals("NonSpecific44", firstName("  NonSpecific44 "))
    }
}
