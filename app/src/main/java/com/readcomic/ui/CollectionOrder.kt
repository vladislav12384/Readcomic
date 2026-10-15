package com.readcomic.ui

import com.readcomic.data.ComicEntry
import com.readcomic.data.NaturalOrder
import com.readcomic.data.Series
import com.readcomic.data.SeriesInfo

/** How the comics stand on the shelves. */
enum class ShelfOrder(val label: String) {
    TITLE("По алфавиту"),
    ADDED("Недавно добавленные"),
    READ("Недавно читал"),
}

/** A comic in its place on the shelf: with [series] it stands with the rest of that series. */
class Placed(val entry: ComicEntry, val series: String?, val label: String)

object CollectionOrder {
    /**
     * The comics in shelf order. Alphabetically, a series stands where its name comes, its issues
     * in order of volume and number, and their labels show just that ("№ 12"); the other orders
     * do not keep series together.
     */
    fun arrange(comics: List<ComicEntry>, order: ShelfOrder): List<Placed> = when (order) {
        ShelfOrder.TITLE -> byTitle(comics)
        ShelfOrder.ADDED -> comics.sortedByDescending { it.addedAt }.map { Placed(it, null, it.title) }
        ShelfOrder.READ -> comics
            .sortedWith(compareByDescending<ComicEntry> { it.lastReadAt }.thenByDescending { it.addedAt })
            .map { Placed(it, null, it.title) }
    }

    private fun byTitle(comics: List<ComicEntry>): List<Placed> {
        // Comics on their own and whole series, in one alphabet.
        class Block(val name: String, val placed: List<Placed>)
        val units = ArrayList<Block>()
        comics.filter { it.series == null }.forEach { units += Block(it.title, listOf(Placed(it, null, it.title))) }
        comics.filter { it.series != null }.groupBy { Series.keyOf(it.series!!) }.values.forEach { members ->
            val name = members.first().series!!
            val issues = members
                .map { it to Series.of(it.title) }
                .sortedWith(
                    compareBy<Pair<ComicEntry, SeriesInfo>> { it.second.volume ?: 0f }
                        .thenBy { it.second.number ?: -1f }
                        .thenComparator { a, b -> compareNames(a.first.title, b.first.title) },
                )
                .map { (entry, info) -> Placed(entry, name, info.place?.takeIf { Series.keyOf(info.name) == Series.keyOf(name) } ?: entry.title) }
            units += Block(name, issues)
        }
        return units.sortedWith { a, b -> compareNames(a.name, b.name) }.flatMap { it.placed }
    }

    /** "Batman 2" before "Batman 10", "Ёж" with "Еж", whatever the case. */
    fun compareNames(a: String, b: String): Int = NaturalOrder.compare(normalize(a), normalize(b))

    private fun normalize(text: String) = text.lowercase().replace('ё', 'е').trim()

    /**
     * Whether [entry] is what is searched for: every word of [query] is found in its title,
     * series, writer or year, whatever the case.
     */
    fun matches(entry: ComicEntry, query: String): Boolean {
        val words = normalize(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = normalize(listOfNotNull(entry.title, entry.series, entry.writer, entry.year?.toString()).joinToString(" "))
        return words.all { it in haystack }
    }
}
