package com.leatherbound.listening.data

/** "Ursula K. Le Guin" sorts under L, "Ludwig van Beethoven" under V. */
fun authorSortKey(author: String): String {
    val parts = author.trim().split(Regex("\\s+"))
    if (parts.size < 2) return author.trim().lowercase()
    val particles = setOf("le", "de", "van", "von", "du", "da", "del", "la", "di")
    val surnameStart = parts.indexOfFirst { it.lowercase() in particles }.takeIf { it > 0 } ?: (parts.size - 1)
    return (parts.drop(surnameStart) + parts.take(surnameStart)).joinToString(" ").lowercase()
}

const val NO_GENRE = "No genre"

/** Every genre a book is listed under, or [NO_GENRE]. */
fun genresOf(book: com.leatherbound.listening.data.db.BookEntity): List<String> =
    book.genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { it.lowercase() }?.ifEmpty { null } ?: listOf(NO_GENRE)

/**
 * Books grouped by genre, A–Z with "No genre" last. A book appears under each of its genres;
 * within a genre, books run by author then title.
 */
fun groupByGenre(books: List<com.leatherbound.listening.data.db.BookEntity>): List<Pair<String, List<com.leatherbound.listening.data.db.BookEntity>>> =
    books.flatMap { b -> genresOf(b).map { it to b } }
        // "Fantasy" and "fantasy" are one genre, named the way most books spell it.
        .groupBy { it.first.lowercase() }
        .map { (_, pairs) ->
            val name = pairs.groupingBy { it.first }.eachCount().maxByOrNull { it.value }!!.key
            name to pairs.map { it.second }.distinctBy { it.id }.sortedWith(compareBy({ authorSortKey(it.author) }, { it.sortTitle.lowercase() }))
        }
        .sortedWith(compareBy({ it.first == NO_GENRE }, { it.first.lowercase() }))
