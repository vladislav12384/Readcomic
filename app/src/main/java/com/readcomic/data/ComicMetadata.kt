package com.readcomic.data

/** What a comic file says about itself (ComicInfo.xml in CBZ/CBR, the OPF of an EPUB). */
data class ComicMetadata(
    val title: String? = null,
    val series: String? = null,
    val number: String? = null,
    val year: Int? = null,
    val writer: String? = null,
    val summary: String? = null,
) {
    /** "Series #12", the series, or the title; null when there is nothing useful. */
    val displayTitle: String?
        get() = when {
            series != null && number != null -> "$series #$number"
            series != null -> title?.takeIf { it != series }?.let { "$series: $it" } ?: series
            else -> title
        }

    val isEmpty get() = this == ComicMetadata()

    companion object {
        /** ComicInfo.xml, the de facto standard of comic archives (ComicRack, Kavita, Komga…). */
        fun fromComicInfo(xml: String): ComicMetadata? {
            fun tag(name: String) = Xml.text(xml, name)
            val metadata = ComicMetadata(
                title = tag("Title"),
                series = tag("Series"),
                number = tag("Number"),
                year = tag("Year")?.toIntOrNull()?.takeIf { it in 1800..2200 },
                writer = tag("Writer"),
                summary = tag("Summary"),
            )
            return metadata.takeUnless { it.isEmpty }
        }

        /** The Dublin Core part of an EPUB package document. */
        fun fromOpf(opf: String): ComicMetadata? {
            val metadata = ComicMetadata(
                title = Xml.text(opf, "dc:title"),
                year = Xml.text(opf, "dc:date")?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2200 },
                writer = Xml.text(opf, "dc:creator"),
                summary = Xml.text(opf, "dc:description")?.let(Xml::stripTags),
                series = Regex("""<meta[^>]+name\s*=\s*["']calibre:series["'][^>]+content\s*=\s*["']([^"']+)["']""")
                    .find(opf)?.groupValues?.get(1)?.let(Xml::unescape),
                number = Regex("""<meta[^>]+name\s*=\s*["']calibre:series_index["'][^>]+content\s*=\s*["']([^"']+)["']""")
                    .find(opf)?.groupValues?.get(1)?.removeSuffix(".0"),
            )
            return metadata.takeUnless { it.isEmpty }
        }
    }
}

/** Just enough XML for metadata and EPUB packages; real files are often a little broken. */
internal object Xml {
    fun text(xml: String, tag: String): String? {
        val match = Regex("""<$tag(?:\s[^>]*)?>(.*?)</$tag\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml) ?: return null
        return unescape(match.groupValues[1].replace(Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL), "$1"))
            .trim()
            .ifEmpty { null }
    }

    /** Attributes of every [tag] element, in document order. */
    fun elements(xml: String, tag: String): List<Map<String, String>> =
        Regex("""<(?:\w+:)?$tag\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(xml).map { element ->
            Regex("""([\w:.-]+)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)
                .findAll(element.groupValues[1])
                .associate { it.groupValues[1].lowercase() to unescape(it.groupValues[3]) }
        }.toList()

    fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: "" }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: "" }
        .replace("&amp;", "&")

    fun stripTags(text: String) = text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * The reading order of an EPUB comic: the spine of its package, each spine document giving
 * the pictures it shows. Null when the archive is not an EPUB or names no pictures.
 */
object EpubOrder {
    const val CONTAINER = "META-INF/container.xml"

    /** Text files needed for the order, to be read from the archive: anything that is not a picture. */
    fun isTextFile(name: String) = name.substringAfterLast('.').lowercase() in setOf("xml", "opf", "xhtml", "html", "htm", "svg")

    fun packagePath(container: String): String? =
        Xml.elements(container, "rootfile").firstNotNullOfOrNull { it["full-path"] }

    fun pages(opfPath: String, opf: String, texts: Map<String, String>, images: Set<String>): List<String>? {
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = Xml.elements(opf, "item").mapNotNull { item ->
            val id = item["id"] ?: return@mapNotNull null
            val href = item["href"] ?: return@mapNotNull null
            id to (resolve(base, href) to (item["media-type"] ?: ""))
        }.toMap()
        val order = LinkedHashSet<String>()
        for (ref in Xml.elements(opf, "itemref")) {
            val (path, type) = manifest[ref["idref"]] ?: continue
            if (type.startsWith("image/") || path in images) {
                if (path in images) order += path
                continue
            }
            val document = texts[path] ?: continue
            val dir = path.substringBeforeLast('/', "")
            for (src in pictureLinks(document)) {
                val image = resolve(dir, src)
                if (image in images) order += image
            }
        }
        return order.toList().ifEmpty { null }
    }

    private fun pictureLinks(document: String): List<String> {
        val img = Regex("""<img\b[^>]*?\bsrc\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        val svg = Regex("""<image\b[^>]*?\b(?:xlink:)?href\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
        return (img.findAll(document).map { it.range.first to it.groupValues[2] } + svg.findAll(document).map { it.range.first to it.groupValues[2] })
            .sortedBy { it.first }
            .map { Xml.unescape(it.second) }
            .toList()
    }

    /** [href] relative to the directory [base], without "..", fragments or %-escapes. */
    fun resolve(base: String, href: String): String {
        val clean = java.net.URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
        val parts = ArrayList<String>()
        val start = if (clean.startsWith("/")) emptyList() else base.split('/').filter { it.isNotEmpty() }
        for (segment in start + clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }
}
