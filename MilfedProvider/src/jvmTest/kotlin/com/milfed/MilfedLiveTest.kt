package com.milfed

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Runs the real MilfedApi against the live site.
 *
 * These are the same requests the plugin makes on the phone, so a green run
 * here means the API contract (instance token, header name, parameter names,
 * pagination, response shapes) still holds.
 */
class MilfedLiveTest {

    @Test
    fun theApiAnswersWithTheInstanceToken() {
        val page = MilfedApi.releases(type = "scene", limit = 1, offset = 0)
        assertTrue(page.result.isNotEmpty(), "the catalogue must not be empty")
    }

    @Test
    fun aListingCarriesEverythingCloudStreamNeeds() {
        val page = MilfedApi.releases(type = "scene", limit = 20, offset = 0)
        assertEquals(20, page.result.size)
        val r = page.result.first()
        assertTrue(r.title.isNotBlank(), "title")
        assertTrue(r.description.isNotBlank(), "description")
        assertNotNull(r.images?.poster, "poster")
        assertNotNull(r.preview(), "mediabook")
        assertTrue(r.preview()!!.files.isNotEmpty(), "preview files")
        assertTrue(
            r.preview()!!.files.values.any { it.urls["view"]?.startsWith("http") == true },
            "a preview file must carry a view url"
        )
    }

    @Test
    fun pagesAreDisjoint() {
        val p1 = MilfedApi.releases(type = "scene", limit = 20, offset = 0).result.map { it.id }
        val p2 = MilfedApi.releases(type = "scene", limit = 20, offset = 20).result.map { it.id }
        assertEquals(20, p1.size)
        assertEquals(20, p2.size)
        assertTrue(p1.intersect(p2.toSet()).isEmpty(), "page 1 and page 2 must not overlap")
    }

    @Test
    fun everySectionReturnsContent() {
        val latest = MilfedApi.releases(type = "scene", limit = 20, offset = 0).result
        val popular = MilfedApi.releases(type = "scene", limit = 20, offset = 0, orderBy = "-stats.views").result
        val milf = MilfedApi.releases(type = "scene", limit = 20, offset = 0, extra = mapOf("tagId" to "79")).result
        val actors = MilfedApi.actors(limit = 20, offset = 0).result

        assertTrue(latest.isNotEmpty(), "latest")
        assertTrue(popular.isNotEmpty(), "popular")
        assertTrue(milf.isNotEmpty(), "MILF tag")
        assertTrue(actors.isNotEmpty(), "actors")
        assertTrue(
            latest.map { it.id }.intersect(popular.map { it.id }.toSet()).size < 20,
            "latest and popular must order differently"
        )
        assertTrue(
            MilfedApi.releases(type = "scene", limit = 1, extra = mapOf("tagId" to "79")).meta!!.total < 1331,
            "the MILF tag must narrow the catalogue"
        )
    }

    @Test
    fun actorPagesAreDisjoint() {
        val p1 = MilfedApi.actors(limit = 20, offset = 0).result.map { it.id }
        val p2 = MilfedApi.actors(limit = 20, offset = 20).result.map { it.id }
        assertEquals(20, p1.size)
        assertEquals(20, p2.size)
        assertTrue(p1.intersect(p2.toSet()).isEmpty(), "actor pages must not overlap")
    }

    @Test
    fun releaseDetailResolvesById() {
        val id = MilfedApi.releases(type = "scene", limit = 1, offset = 0).result.first().id
        val detail = MilfedApi.release(id)
        assertNotNull(detail, "release $id")
        assertEquals(id, detail!!.id)
        assertTrue(detail.title.isNotBlank(), "the detail carries the title")
        assertTrue(detail.preview()?.files?.isNotEmpty() == true, "the detail carries the preview")
    }

    @Test
    fun filteringByActorNarrowsTheCatalogue() {
        val actorId = MilfedApi.actors(limit = 1, offset = 0).result.first().id
        val scenes = MilfedApi.releases(
            type = "scene", limit = 40, offset = 0,
            extra = mapOf("actorId" to actorId.toString())
        ).result
        assertTrue(scenes.isNotEmpty(), "actor $actorId must have scenes")
        assertTrue(
            scenes.all { scene -> scene.actors.any { it.id == actorId } },
            "every scene must belong to the actor that was filtered on"
        )
    }
}
