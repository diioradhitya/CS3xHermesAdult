package com.avtube

/**
 * ByseExtractor: handles f7hyg4q.org direct video links.
 *
 * Uses f7hyg4q.org as API base with NON-embed API paths
 * (e.g. /api/videos/{code}/details instead of /api/videos/{code}/embed/details).
 *
 * This works for /d/ (direct) path links like:
 *   https://f7hyg4q.org/d/uo0yqe48yqtr
 */
class ByseExtractor : YstreamExtractor() {
    override var name = "Byse"
    override var mainUrl = "https://f7hyg4q.org"

    /** Use non-embed API paths (no /embed/ prefix) */
    override val useEmbedPath: Boolean = false

    /** /d/ path for direct video links */
    override fun getParentPath(code: String): String = "/d/$code/"
}
