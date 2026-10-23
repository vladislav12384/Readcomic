package com.readcomic.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleasesTest {
    private val sha = "5b8e32d3c151f2fda945b56932861845215fdd8ed49188dfcdd41a266808362f"

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, body: String? = null, apk: String? = null) =
        Release(
            tag = tag,
            prerelease = prerelease,
            draft = draft,
            body = body,
            assets = listOfNotNull(
                ReleaseAsset("Readcomic-debug.apk", "https://example.org/$tag/debug.apk", 21_000_000, null),
                apk?.let { ReleaseAsset(it, "https://example.org/$tag/$it", 10_139_843, "sha256:$sha") },
            ),
        )

    @Test
    fun buildNumbersComeFromTheTags() {
        assertEquals(31, Releases.buildOf("build-31", preview = false))
        assertEquals(29, Releases.buildOf("preview-29", preview = true))
        assertNull(Releases.buildOf("preview-29", preview = false))
        assertNull(Releases.buildOf("build-31", preview = true))
        assertNull(Releases.buildOf("build-x", preview = false))
        assertNull(Releases.buildOf("v1.0", preview = false))
        assertNull(Releases.buildOf("build-0", preview = false))
    }

    @Test
    fun theNewestBuildOfTheMainAppIsOffered() {
        val releases = listOf(
            release("build-33", apk = "Readcomic.apk", body = "- Новое\n\n---\n\nостальное"),
            release("preview-34", prerelease = true, apk = "Readcomic-preview.apk"),
            release("build-32", apk = "Readcomic.apk"),
            release("build-31", apk = "Readcomic.apk"),
        )
        val offer = Releases.newest(releases, preview = false, current = 31)!!
        assertEquals(33, offer.build)
        assertEquals("https://example.org/build-33/Readcomic.apk", offer.url)
        assertEquals(10_139_843L, offer.size)
        assertEquals(sha, offer.sha256)
        assertEquals("• Новое", offer.notes)
        // Up to date, or ahead: nothing to offer.
        assertNull(Releases.newest(releases, preview = false, current = 33))
        assertNull(Releases.newest(releases, preview = false, current = 40))
    }

    @Test
    fun thePreviewFollowsOnlyItsOwnPreReleases() {
        val releases = listOf(
            release("build-40", apk = "Readcomic.apk"),
            release("preview-36", prerelease = true, apk = "Readcomic-preview.apk"),
            release("preview-35", prerelease = true, apk = "Readcomic-preview.apk"),
        )
        assertEquals(36, Releases.newest(releases, preview = true, current = 29)!!.build)
        assertEquals(40, Releases.newest(releases, preview = false, current = 31)!!.build)
    }

    @Test
    fun draftsAndReleasesWithoutTheApkAreSkipped() {
        val releases = listOf(
            release("build-35", draft = true, apk = "Readcomic.apk"),
            release("build-34"),
            release("build-33", prerelease = true, apk = "Readcomic.apk"),
            release("build-32", apk = "Readcomic.apk"),
        )
        assertEquals(32, Releases.newest(releases, preview = false, current = 31)!!.build)
        assertNull(Releases.newest(releases.take(3), preview = false, current = 31))
    }

    @Test
    fun notesAreWhatStandsAboveTheRule() {
        val body = "- Приложение обновляется само\r\n* **Важно**: раз в день\r\n\r\n---\r\n\r\n**Readcomic.apk** — обычная версия"
        assertEquals("• Приложение обновляется само\n• Важно: раз в день", Releases.notesOf(body))
        // Releases made before there were notes: no rule, no notes.
        assertNull(Releases.notesOf("Сборка из коммита abc.\n\n**Readcomic.apk** — обычная версия"))
        assertNull(Releases.notesOf("\n---\nвсё остальное"))
        assertNull(Releases.notesOf(null))
        val long = "x".repeat(2000) + "\n---\n"
        val cut = Releases.notesOf(long)!!
        assertEquals('…', cut.last())
        assertEquals(601, cut.length)
    }

    @Test
    fun digestsAreReadOnlyWhenTheyAreSha256() {
        assertEquals(sha, Releases.sha256Of("sha256:$sha"))
        assertEquals(sha, Releases.sha256Of("SHA256:${sha.uppercase()}"))
        assertNull(Releases.sha256Of("md5:abc"))
        assertNull(Releases.sha256Of("sha256:xyz"))
        assertNull(Releases.sha256Of(null))
    }

    @Test
    fun theApkOfEachApp() {
        assertEquals("Readcomic.apk", Releases.apkName(preview = false))
        assertEquals("Readcomic-preview.apk", Releases.apkName(preview = true))
    }
}
