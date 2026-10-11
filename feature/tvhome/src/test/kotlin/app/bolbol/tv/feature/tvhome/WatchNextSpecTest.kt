package app.bolbol.tv.feature.tvhome

import app.bolbol.tv.core.model.ContinueWatchingItem
import app.bolbol.tv.core.model.PlaybackProgress
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.WatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val PKG = "app.bolbol.tv"

internal fun summary(
    id: Long,
    title: String = "Title $id",
    faTitle: String = "",
    posterUrl: String? = "https://img.example/$id/poster.jpg",
    backdropUrl: String? = "https://img.example/$id/backdrop.jpg",
) = TitleSummary(
    id = id, type = null, title = title, faTitle = faTitle, posterUrl = posterUrl, backdropUrl = backdropUrl,
    year = 2025, imdbRating = 7.1f, hasDub = false, hasSubtitle = true,
)

internal fun item(
    key: WatchKey,
    positionMs: Long = 600_000,
    durationMs: Long = 6_000_000,
    updatedAtMs: Long = 1_000,
    completed: Boolean = false,
    title: TitleSummary = summary(key.postId),
) = ContinueWatchingItem(title, PlaybackProgress(key, positionMs, durationMs, updatedAtMs, completed))

class WatchNextSpecTest {

    @Test
    fun `movie maps to a movie program with a 16 by 9 backdrop`() {
        val spec = item(
            WatchKey(353371),
            positionMs = 1_234_000, durationMs = 5_400_000, updatedAtMs = 99,
            title = summary(353371, title = "The Naked Gun", faTitle = "اسلحه برهنه"),
        ).toWatchNextSpec(PKG)!!

        assertEquals(WatchKey(353371), spec.key)
        assertEquals(ProgramKind.MOVIE, spec.kind)
        assertEquals("اسلحه برهنه", spec.title)
        assertNull(spec.seasonNumber)
        assertNull(spec.episodeNumber)
        assertNull(spec.episodeTitle)
        assertEquals("https://img.example/353371/backdrop.jpg", spec.posterUrl)
        assertEquals(PosterShape.WIDE_16_9, spec.posterShape)
        assertEquals(1_234_000, spec.positionMs)
        assertEquals(5_400_000, spec.durationMs)
        assertEquals(99L, spec.lastEngagementMs)
        assertEquals("bolbol://play/353371/0/0", spec.deepLink)
        assertEquals(PKG, spec.appPackage)
    }

    @Test
    fun `episode maps to a tv episode with persian season and episode label`() {
        val spec = item(WatchKey(7, 1, 2), title = summary(7, title = "Severance")).toWatchNextSpec(PKG)!!

        assertEquals(ProgramKind.TV_EPISODE, spec.kind)
        assertEquals("Severance", spec.title) // no Persian title -> Latin title
        assertEquals(1, spec.seasonNumber)
        assertEquals(2, spec.episodeNumber)
        assertEquals("فصل ۱ · قسمت ۲", spec.episodeTitle)
        assertEquals("bolbol://play/7/1/2", spec.deepLink)
    }

    @Test
    fun `multi digit numbers use persian digits`() {
        assertEquals("فصل ۱۲ · قسمت ۱۰۳", episodeLabel(12, 103))
    }

    @Test
    fun `falls back to the poster when there is no backdrop and drops non http images`() {
        val noBackdrop = item(WatchKey(5), title = summary(5, backdropUrl = null)).toWatchNextSpec(PKG)!!
        assertEquals("https://img.example/5/poster.jpg", noBackdrop.posterUrl)
        assertEquals(PosterShape.POSTER_2_3, noBackdrop.posterShape)

        val junk = item(WatchKey(6), title = summary(6, backdropUrl = "  ", posterUrl = "file:///x.jpg")).toWatchNextSpec(PKG)!!
        assertNull(junk.posterUrl)
    }

    @Test
    fun `blank persian title falls back and a fully blank title is skipped`() {
        assertEquals("Latin", item(WatchKey(1), title = summary(1, title = "Latin", faTitle = "  ")).toWatchNextSpec(PKG)!!.title)
        assertNull(item(WatchKey(2), title = summary(2, title = " ", faTitle = "")).toWatchNextSpec(PKG))
    }

    @Test
    fun `finished or unplayable items are not published`() {
        assertNull(item(WatchKey(1), completed = true).toWatchNextSpec(PKG))
        assertNull(item(WatchKey(1), durationMs = 0).toWatchNextSpec(PKG))
        assertNull(item(WatchKey(1), durationMs = Int.MAX_VALUE.toLong() + 1).toWatchNextSpec(PKG))
    }

    @Test
    fun `position is clamped into the duration`() {
        assertEquals(5_000, item(WatchKey(1), positionMs = 9_000, durationMs = 5_000).toWatchNextSpec(PKG)!!.positionMs)
        assertEquals(0, item(WatchKey(1), positionMs = -3, durationMs = 5_000).toWatchNextSpec(PKG)!!.positionMs)
    }

    @Test
    fun `selection keeps one program per title, newest first, capped`() {
        val items = listOf(
            item(WatchKey(1, 1, 1), updatedAtMs = 10),
            item(WatchKey(1, 1, 3), updatedAtMs = 50), // same series, more recent: wins
            item(WatchKey(1, 1, 2), updatedAtMs = 30),
            item(WatchKey(2), updatedAtMs = 40),
            item(WatchKey(3), updatedAtMs = 5, completed = true),
            item(WatchKey(4), updatedAtMs = 60),
        )
        val specs = selectWatchNextSpecs(items, PKG)
        assertEquals(listOf(WatchKey(4), WatchKey(1, 1, 3), WatchKey(2)), specs.map { it.key })

        assertEquals(listOf(WatchKey(4), WatchKey(1, 1, 3)), selectWatchNextSpecs(items, PKG, max = 2).map { it.key })
    }

    @Test
    fun `fingerprint reacts to every visible change and is stable otherwise`() {
        val base = item(WatchKey(7, 1, 2)).toWatchNextSpec(PKG)!!
        assertEquals(base.fingerprint(), item(WatchKey(7, 1, 2)).toWatchNextSpec(PKG)!!.fingerprint())

        val variants = listOf(
            item(WatchKey(7, 1, 2), positionMs = 700_000),
            item(WatchKey(7, 1, 2), durationMs = 7_000_000),
            item(WatchKey(7, 1, 2), updatedAtMs = 2_000),
            item(WatchKey(7, 1, 3)),
            item(WatchKey(7, 1, 2), title = summary(7, faTitle = "نام")),
            item(WatchKey(7, 1, 2), title = summary(7, backdropUrl = "https://other.example/b.jpg")),
        ).map { it.toWatchNextSpec(PKG)!!.fingerprint() }
        variants.forEach { assertNotEquals(base.fingerprint(), it) }
        assertEquals(variants.size, variants.toSet().size)
        assertNotEquals(base.fingerprint(), base.copy(appPackage = "other.pkg").fingerprint())
    }

    @Test
    fun `list digest is order sensitive and distinguishes empty`() {
        val a = item(WatchKey(1)).toWatchNextSpec(PKG)!!
        val b = item(WatchKey(2)).toWatchNextSpec(PKG)!!
        assertEquals(fingerprintOf(listOf(a, b)), fingerprintOf(listOf(a, b)))
        assertNotEquals(fingerprintOf(listOf(a, b)), fingerprintOf(listOf(b, a)))
        assertNotEquals(fingerprintOf(listOf(a)), fingerprintOf(emptyList()))
        assertNotNull(fingerprintOf(emptyList()))
    }
}
