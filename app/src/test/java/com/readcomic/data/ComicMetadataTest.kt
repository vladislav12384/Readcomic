package com.readcomic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicMetadataTest {
    @Test
    fun comicInfoGivesSeriesAndNumber() {
        val xml = """
            <?xml version="1.0"?>
            <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <Title>The Long Night</Title>
              <Series>Saga &amp; Co</Series>
              <Number>12</Number>
              <Year>2019</Year>
              <Writer>Brian K. Vaughan</Writer>
              <Summary><![CDATA[Two lovers <b>on the run</b>.]]></Summary>
            </ComicInfo>
        """.trimIndent()
        val metadata = ComicMetadata.fromComicInfo(xml)!!
        assertEquals("Saga & Co #12", metadata.displayTitle)
        assertEquals(2019, metadata.year)
        assertEquals("Brian K. Vaughan", metadata.writer)
        assertEquals("Two lovers <b>on the run</b>.", metadata.summary)
    }

    @Test
    fun emptyComicInfoIsNothing() {
        assertNull(ComicMetadata.fromComicInfo("<ComicInfo><Title></Title></ComicInfo>"))
    }

    @Test
    fun epubFollowsTheSpine() {
        val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """
            <package><metadata><dc:title>Город теней</dc:title><dc:creator>Автор</dc:creator><dc:date>2021-05-01</dc:date></metadata>
            <manifest>
              <item id="p2" href="Text/page%202.xhtml" media-type="application/xhtml+xml"/>
              <item media-type="application/xhtml+xml" href="Text/page10.xhtml" id="p10"/>
              <item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/>
              <item id="i2" href="Images/b.jpg" media-type="image/jpeg"/>
            </manifest>
            <spine><itemref idref="cover"/><itemref idref="p10"/><itemref idref="p2"/></spine></package>
        """.trimIndent()
        val texts = mapOf(
            "OEBPS/Text/page 2.xhtml" to """<html><body><svg><image width="10" xlink:href="../Images/b.jpg"/></svg></body></html>""",
            "OEBPS/Text/page10.xhtml" to """<html><body><img alt="" src="../Images/a.jpg"/></body></html>""",
        )
        val images = setOf("OEBPS/Images/cover.jpg", "OEBPS/Images/a.jpg", "OEBPS/Images/b.jpg", "OEBPS/Images/unused.png")
        assertEquals("OEBPS/content.opf", EpubOrder.packagePath(container))
        assertEquals(
            listOf("OEBPS/Images/cover.jpg", "OEBPS/Images/a.jpg", "OEBPS/Images/b.jpg"),
            EpubOrder.pages("OEBPS/content.opf", opf, texts, images),
        )
        val metadata = ComicMetadata.fromOpf(opf)!!
        assertEquals("Город теней", metadata.displayTitle)
        assertEquals(2021, metadata.year)
    }

    @Test
    fun relativePathsResolve() {
        assertEquals("a/c.jpg", EpubOrder.resolve("a/b", "../c.jpg"))
        assertEquals("x y.png", EpubOrder.resolve("", "./x%20y.png#frag"))
    }
}
