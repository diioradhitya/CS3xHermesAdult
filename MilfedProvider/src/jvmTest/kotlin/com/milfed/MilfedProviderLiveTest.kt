package com.milfed

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the provider itself through the CloudStream entry points, against
 * the live site. This is the same path the phone takes, so it proves the
 * whole chain: section id -> API query -> SearchResponse with a poster, and
 * release id -> playable link.
 */
class MilfedProviderLiveTest {

    private fun <T> runBlocking(block: suspend () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(res: Result<T>) {
                result = res.getOrNull()
                failure = res.exceptionOrNull()
                latch.countDown()
            }
        })
        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun requestFor(data: String, name: String) =
        MainPageRequest(name = name, data = data, horizontalImages = false)

    /** HomePageResponse carries a list of HomePageList, each with its items. */
    private fun HomePageResponse.items(): List<SearchResponse> =
        items.flatMap { it.list }

    @Test
    fun everyHomeSectionReturnsItemsWithPosters() {
        val provider = MilfedProvider()
        val sections = listOf(
            "latest" to "Latest",
            "popular" to "Populer",
            "milf" to "MILF",
            "actors" to "Model"
        )
        for ((data, name) in sections) {
            val page: HomePageResponse = runBlocking {
                provider.getMainPage(1, requestFor(data, name))
            }
            val items = page.items()
            assertTrue(items.isNotEmpty(), "section $data must not be empty")
            val withPoster = items.count { !it.posterUrl.isNullOrBlank() }
            assertTrue(withPoster > 0, "section $data must carry posters")
            assertTrue(
                items.all { it.url.contains("/scene/") || it.url.contains("/model/") },
                "section $data must link to a detail page"
            )
        }
    }

    @Test
    fun pageTwoReturnsDifferentItems() {
        val provider = MilfedProvider()
        val p1 = runBlocking { provider.getMainPage(1, requestFor("latest", "Latest")) }.items()
        val p2 = runBlocking { provider.getMainPage(2, requestFor("latest", "Latest")) }.items()
        assertTrue(p1.isNotEmpty() && p2.isNotEmpty(), "both pages must have items")
        val ids1 = p1.mapNotNull { it.url.substringAfterLast("/scene/").substringBefore("/").toLongOrNull() }
        val ids2 = p2.mapNotNull { it.url.substringAfterLast("/scene/").substringBefore("/").toLongOrNull() }
        assertTrue(ids1.intersect(ids2.toSet()).isEmpty(), "page 1 and page 2 must not overlap")
    }

    @Test
    fun loadReturnsTheDetails() {
        val provider = MilfedProvider()
        val item = runBlocking { provider.getMainPage(1, requestFor("latest", "Latest")) }.items().first()
        val loaded = runBlocking { provider.load(item.url) }
        assertNotNull(loaded, "load() must answer")
        assertTrue(loaded!!.name.isNotBlank(), "the title must be set")
        assertTrue(!loaded.plot.isNullOrBlank(), "the plot must be set")
        assertTrue(!loaded.posterUrl.isNullOrBlank(), "the poster must be set")
    }

    @Test
    fun loadLinksEmitsAPlayableFile() {
        val provider = MilfedProvider()
        val item = runBlocking { provider.getMainPage(1, requestFor("latest", "Latest")) }.items().first()
        val loaded = runBlocking { provider.load(item.url) } as MovieLoadResponse

        val links = mutableListOf<ExtractorLink>()
        val ok = runBlocking {
            provider.loadLinks(loaded.dataUrl, false, { _: SubtitleFile -> }, { link -> links.add(link) })
        }
        assertTrue(ok, "loadLinks must report success")
        assertTrue(links.isNotEmpty(), "at least one link must be emitted")
        val url = links.first().url
        assertTrue(url.startsWith("http"), "the link must be absolute: $url")
        assertTrue(url.endsWith(".mp4"), "the link must be an mp4: $url")
    }

    @Test
    fun searchFindsSomething() {
        val provider = MilfedProvider()
        val results = runBlocking { provider.search("milf") }
        assertTrue(results.isNotEmpty(), "search must find something")
    }
}
