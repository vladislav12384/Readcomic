package com.readcomic.data

/** Where a comic belongs in a collection: its series and its place in it. */
data class SeriesInfo(
    /** The series as shown ("Batman"); the whole title for a comic that is not numbered. */
    val name: String,
    /** [name] for grouping: case, "ё", punctuation and spacing do not matter. */
    val key: String,
    /** Volume ("Vol. 2", "Том 2"), null if there is none. */
    val volume: Float? = null,
    /** Issue or chapter ("#12", "Глава 25", a number at the end), null if there is none. */
    val number: Float? = null,
    /** The issue as written, "12" or "3.5". */
    val numberText: String? = null,
    val volumeText: String? = null,
) {
    /** A short label for its place in the series: "№ 12", "Том 2", "Том 2 · № 25". */
    val place: String?
        get() = when {
            volumeText != null && numberText != null -> "Том $volumeText · № $numberText"
            volumeText != null -> "Том $volumeText"
            numberText != null -> "№ $numberText"
            else -> null
        }
}

/**
 * Tells the series and the number of a comic from its title or file name, the way people name
 * them: "Batman #12", "Saga 001 (2012) (Digital)", "One Piece Vol. 3 Ch. 25", "Хроники — Том 2",
 * "Watchmen 01 of 12". Years in brackets, scan groups and the like are left out.
 */
object Series {
    private const val NUMBER = """(\d{1,4}(?:[.,]\d{1,2})?)"""

    private val BRACKETS = Regex("""\([^)]*\)|\[[^]]*]|\{[^}]*}""")
    private val VOLUME = Regex(
        """(?:\b(?:vol(?:ume)?|tome|book|bk)\b\.?|(?<![\p{L}\d])(?:том|книга|т\.)|\bv(?=\d))\s*$NUMBER""",
        RegexOption.IGNORE_CASE,
    )
    private val ISSUE = Regex(
        """(?:#|№|\b(?:no|nr|issue|ch|chapter|part|pt)\b\.?|(?<![\p{L}\d])(?:выпуск|вып\.|номер|глава|гл\.|часть))\s*$NUMBER""",
        RegexOption.IGNORE_CASE,
    )
    private val OF_TOTAL = Regex("""(?<![\p{L}\d.])$NUMBER\s+(?:of|из)\s+\d{1,4}\b""", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("""(?<![\p{L}\d.])$NUMBER\s*$""")
    private val SPACES = Regex("""\s+""")
    private val NOT_WORD = Regex("""[^\p{L}\d]+""")

    fun of(title: String): SeriesInfo {
        val clean = BRACKETS.replace(title.replace('_', ' '), " ").replace(SPACES, " ").trim()
        val volume = VOLUME.find(clean)
        val issue = ISSUE.find(clean) ?: OF_TOTAL.find(clean)
            ?: TRAILING.find(clean)?.takeIf { volume == null || it.range.first > volume.range.last }
                ?.takeUnless { looksLikeYear(it.groupValues[1]) && volume == null }
        // The series is what comes before the first of them.
        val cut = listOfNotNull(volume?.range?.first, issue?.range?.first).minOrNull()
        val name = (if (cut != null) clean.substring(0, cut) else clean).trim(' ', '-', '–', '—', ':', ',', '.', '#', '/')
        if (name.isEmpty()) return SeriesInfo(title.trim(), keyOf(title))
        return SeriesInfo(
            name = name,
            key = keyOf(name),
            volume = volume?.groupValues?.get(1)?.let(::parse),
            number = issue?.groupValues?.get(1)?.let(::parse),
            numberText = issue?.groupValues?.get(1)?.let(::tidy),
            volumeText = volume?.groupValues?.get(1)?.let(::tidy),
        )
    }

    /** Same series whatever the case, "ё" or punctuation. */
    fun keyOf(name: String): String = name.lowercase()
        .replace('ё', 'е')
        .replace(NOT_WORD, " ")
        .trim()

    /** A bare four-digit number like 2099 at the end is part of the name ("Spider-Man 2099"), not an issue. */
    private fun looksLikeYear(text: String) = text.length == 4 && text.toIntOrNull()?.let { it in 1900..2100 } == true

    private fun parse(text: String) = text.replace(',', '.').toFloatOrNull()

    /** "001" reads as "1", "3,5" as "3.5". */
    private fun tidy(text: String): String {
        val dot = text.replace(',', '.')
        val whole = dot.substringBefore('.').trimStart('0').ifEmpty { "0" }
        return if ('.' in dot) "$whole.${dot.substringAfter('.')}" else whole
    }
}
