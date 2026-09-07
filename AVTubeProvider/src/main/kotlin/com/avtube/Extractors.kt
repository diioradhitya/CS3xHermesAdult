package com.avtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8

/**
 * VidHide / EarnVids family extractor (morencius.com, dingtezuni.com, ...).
 * Unpacks the packed/eval'd JWPlayer script and extracts the m3u8 HLS links.
 */
open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if (result.contains("var links")) result = result.substringAfter("var links")
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { match ->
            generateM3u8(
                name,
                fixUrl(match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String = when {
        url.contains("/d/") -> url.replace("/d/", "/v/")
        url.contains("/download/") -> url.replace("/download/", "/v/")
        url.contains("/file/") -> url.replace("/file/", "/v/")
        else -> url.replace("/f/", "/v/")
    }
}

class Morencius : Dingtezuni() {
    override var name = "Morencius"
    override var mainUrl = "https://morencius.com"
}