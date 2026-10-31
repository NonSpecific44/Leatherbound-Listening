package com.leatherbound.listening.data

/** The shelves on the Home screen, in their default order. */
enum class HomeSection(val label: String, val description: String) {
    UP_NEXT("Up next", "Your listening list"),
    JUMP_BACK_IN("Jump back in", "Books you've started"),
    ON_DEVICE("On device", "Downloaded books"),
    COLLECTIONS("Collections", "From Plex, Jellyfin and your folders"),
    RECENTLY_ADDED("Recently added", "Newest in your libraries"),
}

/** Which Home shelves show, and in what order. Stored as e.g. "UP_NEXT,-ON_DEVICE,JUMP_BACK_IN" (- = hidden). */
data class HomeLayout(val order: List<HomeSection>, val hidden: Set<HomeSection>) {
    val visible: List<HomeSection> get() = order.filter { it !in hidden }

    fun encode(): String = order.joinToString(",") { (if (it in hidden) "-" else "") + it.name }

    companion object {
        val DEFAULT = HomeLayout(HomeSection.entries, emptySet())

        /** Unknown names are dropped; sections added in later versions appear at the end, shown. */
        fun parse(stored: String?): HomeLayout {
            if (stored.isNullOrBlank()) return DEFAULT
            val order = mutableListOf<HomeSection>()
            val hidden = mutableSetOf<HomeSection>()
            stored.split(",").forEach { token ->
                val name = token.trim().removePrefix("-")
                val section = HomeSection.entries.firstOrNull { it.name == name } ?: return@forEach
                if (section in order) return@forEach
                order += section
                if (token.trim().startsWith("-")) hidden += section
            }
            order += HomeSection.entries.filter { it !in order }
            return HomeLayout(order, hidden)
        }
    }
}
