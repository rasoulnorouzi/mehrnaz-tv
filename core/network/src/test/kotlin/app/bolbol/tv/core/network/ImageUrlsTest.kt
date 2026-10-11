package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.network.dto.DetailDto
import app.bolbol.tv.core.network.dto.HomeEnvelopeDto
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.mapper.ImageUrls
import app.bolbol.tv.core.network.mapper.toDetails
import app.bolbol.tv.core.network.mapper.toHomeSections
import app.bolbol.tv.core.network.mapper.toPage
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUrlsTest {

    private val images = ImageUrls.Default
    private val cdn = "https://majnoonbazar.ir"

    @Test
    fun defaultPrimaryIsTheFirstImageHost() {
        assertEquals("https://majnoonbazar.ir/", NetworkModule.DEFAULT_IMAGE_HOSTS.first())
        assertEquals(listOf("https://majnoonbazar.ir/", "https://seo2024.ir/"), NetworkModule.DEFAULT_IMAGE_HOSTS)
    }

    @Test
    fun apiHostsAreRewrittenToThePrimaryCdnKeepingThePath() {
        val path = "/wp-content/uploads/2026/02/0-76008300-1772116872.webp"
        assertEquals(cdn + path, images.normalize("https://euploadi.com$path"))
        assertEquals(cdn + path, images.normalize("https://uuploadi.ir$path"))
        assertEquals(cdn + path, images.normalize("https://seo2024.ir$path"))
        assertEquals(cdn + path, images.normalize("https://majnoonbazar.ir$path"))
    }

    @Test
    fun schemeAndPortAreReplacedToo() {
        val path = "/wp-content/uploads/a.jpg"
        assertEquals(cdn + path, images.normalize("http://seo2024.ir$path"))
        assertEquals(cdn + path, images.normalize("http://seo2024.ir:8080$path"))
        assertEquals(cdn + path, images.normalize("//seo2024.ir$path"))
    }

    @Test
    fun queryAndFragmentSurvive() {
        assertEquals(
            "$cdn/wp-content/uploads/a.webp?v=3&w=200#x",
            images.normalize("https://euploadi.com/wp-content/uploads/a.webp?v=3&w=200#x"),
        )
    }

    @Test
    fun siteRelativeUploadPathsGetTheCdn() {
        assertEquals("$cdn/wp-content/uploads/a.webp", images.normalize("/wp-content/uploads/a.webp"))
    }

    @Test
    fun urlsOutsideUploadsAreUntouched() {
        for (url in listOf(
            "https://dl20.acenteri.ir/zr/trailers/Movies6/2025/The.N.a.k.e.d.Gun.2025/trailer.mp4",
            "https://euploadi.com/wp-content/themes/site/logo.png",
            "https://euploadi.com/wp-json/mapi/v1/post/all",
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            "https://other.example/blog/wp-content/uploads/a.webp", // uploads below a subdirectory, not the site root
            "https://euploadi.com",
            "not a url",
            "/some/relative.png",
        )) {
            assertEquals(url, images.normalize(url))
        }
    }

    @Test
    fun blankBecomesNullAndTextIsTrimmed() {
        assertNull(images.normalize(null))
        assertNull(images.normalize(""))
        assertNull(images.normalize("   "))
        assertEquals("$cdn/wp-content/uploads/a.webp", images.normalize("  https://euploadi.com/wp-content/uploads/a.webp \n"))
    }

    @Test
    fun theHostIsReadOnEveryCallSoRuntimeChangesApply() {
        var primary = "https://one.example/".toHttpUrl()
        val dynamic = ImageUrls { primary }
        assertEquals("https://one.example/wp-content/uploads/a.webp", dynamic.normalize("https://x.com/wp-content/uploads/a.webp"))
        primary = "http://two.example:8080/".toHttpUrl()
        assertEquals("http://two.example:8080/wp-content/uploads/a.webp", dynamic.normalize("https://x.com/wp-content/uploads/a.webp"))
    }

    // --- the mappers apply it to every image field --------------------------------------------------------------

    private fun TitleSummary.assertImagesOnCdn() {
        for (url in listOfNotNull(posterUrl, backdropUrl)) {
            assertTrue("$id: $url", url.startsWith("$cdn/wp-content/uploads/"))
        }
    }

    @Test
    fun homeItemsUseTheCdn() {
        val items = Samples.home().flatMap { it.items }
        assertTrue(items.isNotEmpty())
        items.forEach { it.assertImagesOnCdn() }
        assertTrue(items.all { it.posterUrl != null })
    }

    @Test
    fun listAndSearchItemsUseTheCdn() {
        for (name in listOf("post_movies.json", "post_series.json", "post_suggestions.json", "post_search_friends.json")) {
            val page = BolboljanJson.decodeFromString(PagedEnvelopeDto.serializer(), Samples.text(name)).toPage(1, null)
            assertTrue(name, page.items.isNotEmpty())
            page.items.forEach { it.assertImagesOnCdn() }
        }
    }

    @Test
    fun detailPosterBackdropPhotosAndRelatedUseTheCdn() {
        for (details in listOf(Samples.movie(), Samples.series())) {
            details.summary.assertImagesOnCdn()
            assertTrue(details.summary.posterUrl != null && details.summary.backdropUrl != null)
            assertTrue(details.actors.isNotEmpty())
            for (person in details.actors + details.directors) {
                person.photoUrl?.let { assertTrue(it, it.startsWith("$cdn/wp-content/uploads/")) }
            }
            assertTrue(details.actors.any { it.photoUrl != null })
            assertTrue(details.related.isNotEmpty())
            details.related.forEach { it.assertImagesOnCdn() }
            // Trailers and streams are not images: left on their own CDN hosts.
            details.trailerUrl?.let { assertTrue(it.startsWith("https://dl20.acenteri.ir/")) }
            val streams = details.streams + details.seasons.flatMap { s -> s.episodes.flatMap { it.streams } }
            assertTrue(streams.all { !it.url.startsWith(cdn) })
        }
    }

    @Test
    fun customPrimaryIsUsedByTheMappers() {
        val custom = ImageUrls { "https://cdn.example/".toHttpUrl() }
        val rows = BolboljanJson.decodeFromString(HomeEnvelopeDto.serializer(), Samples.text("post_all.json")).toHomeSections(custom)
        assertTrue(rows.flatMap { it.items }.all { it.posterUrl!!.startsWith("https://cdn.example/wp-content/uploads/") })

        val envelope = BolboljanJson.decodeFromString(DetailDto.serializer(), """{"id":1,"type":"movie","image":"https://euploadi.com/wp-content/uploads/p.webp","background_image":"","actors":[{"id":2,"name":"A","profile_actor":"https://euploadi.com/wp-content/uploads/a.webp"}]}""")
        val details = envelope.toDetails(custom)
        assertEquals("https://cdn.example/wp-content/uploads/p.webp", details.summary.posterUrl)
        assertNull(details.summary.backdropUrl)
        assertEquals("https://cdn.example/wp-content/uploads/a.webp", details.actors.single().photoUrl)
    }

    @Test
    fun blankImageFieldsStayNull() {
        val page = BolboljanJson.decodeFromString(
            PagedEnvelopeDto.serializer(),
            """{"success":true,"page":1,"total_page":1,"data":[{"id":1,"title":"A","thumbnail":"","background":"  "}]}""",
        ).toPage(1, null)
        assertNull(page.items.single().posterUrl)
        assertNull(page.items.single().backdropUrl)
    }
}
