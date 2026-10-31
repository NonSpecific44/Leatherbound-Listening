package com.leatherbound.listening.data

/** "Ursula K. Le Guin" sorts under L, "Ludwig van Beethoven" under V. */
fun authorSortKey(author: String): String {
    val parts = author.trim().split(Regex("\\s+"))
    if (parts.size < 2) return author.trim().lowercase()
    val particles = setOf("le", "de", "van", "von", "du", "da", "del", "la", "di")
    val surnameStart = parts.indexOfFirst { it.lowercase() in particles }.takeIf { it > 0 } ?: (parts.size - 1)
    return (parts.drop(surnameStart) + parts.take(surnameStart)).joinToString(" ").lowercase()
}
