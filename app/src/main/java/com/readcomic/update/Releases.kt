package com.readcomic.update

/** A file attached to a release on GitHub. */
class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String?)

/** What the app needs of a release published on GitHub. */
class Release(
    val tag: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val body: String?,
    val assets: List<ReleaseAsset>,
)

/** A newer build of the app, ready to be downloaded. */
data class UpdateOffer(
    /** The build number: "build-32" is 32, the same as its version code. */
    val build: Int,
    /** What is new in it, if the release says; plain text. */
    val notes: String?,
    val url: String,
    val size: Long,
    /** SHA-256 of the APK as GitHub reports it (lowercase hex), null if it does not. */
    val sha256: String?,
)

/** Choosing an update among the releases; apart from the network, so it can be tested. */
internal object Releases {
    /** The APK published for the main app, or for the preview ("Readcomic β"). */
    fun apkName(preview: Boolean) = if (preview) "Readcomic-preview.apk" else "Readcomic.apk"

    /** 32 for "build-32" (or "preview-32" for the preview); null for any other tag. */
    fun buildOf(tag: String, preview: Boolean): Int? {
        val prefix = if (preview) "preview-" else "build-"
        if (!tag.startsWith(prefix)) return null
        return tag.substring(prefix.length).toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * What is new, from the text of a release: what stands above its first rule ("---"), as
     * plain text. Releases without a rule have no such part (the rest is the same for all).
     */
    fun notesOf(body: String?): String? {
        val text = body?.replace("\r\n", "\n") ?: return null
        val rule = RULE.find(text) ?: return null
        return text.substring(0, rule.range.first)
            .lines()
            .joinToString("\n") { line ->
                val plain = line.trimEnd().replace("**", "").replace("`", "")
                BULLET.find(plain)?.let { "• " + plain.substring(it.range.last + 1) } ?: plain
            }
            .trim()
            .let { if (it.length > MAX_NOTES) it.take(MAX_NOTES).trimEnd() + "…" else it }
            .ifBlank { null }
    }

    /** The hex of "sha256:<hex>" as GitHub writes it, null if it is anything else. */
    fun sha256Of(digest: String?): String? {
        val hex = digest?.trim()?.lowercase()?.removePrefix("sha256:") ?: return null
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * The newest build after [current] among [releases]: published ones of the main app
     * ([preview] false) or pre-releases of the preview, with their APK attached.
     */
    fun newest(releases: List<Release>, preview: Boolean, current: Long): UpdateOffer? {
        var best: UpdateOffer? = null
        for (release in releases) {
            if (release.draft || release.prerelease != preview) continue
            val build = buildOf(release.tag, preview) ?: continue
            if (build <= current || (best != null && build <= best.build)) continue
            val apk = release.assets.firstOrNull { it.name == apkName(preview) } ?: continue
            best = UpdateOffer(build, notesOf(release.body), apk.url, apk.size, sha256Of(apk.digest))
        }
        return best
    }

    private val RULE = Regex("""^\s*---+\s*$""", RegexOption.MULTILINE)
    private val BULLET = Regex("""^\s*[-*•]\s+""")
    private const val MAX_NOTES = 600
}
