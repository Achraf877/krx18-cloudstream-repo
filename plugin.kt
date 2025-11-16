// CloudStream Plugin for krx18.com (attempts multiple selectors + fallbacks)

package com.krx18

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody
import org.jsoup.nodes.Element

class Krx18Plugin: Plugin() {
    override fun load() {
        registerMainAPI(Krx18())
    }
}

class Krx18 : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "Krx18"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private fun Element.firstText(vararg selectors: String): String {
        for (s in selectors) {
            val el = selectFirst(s)
            if (el != null) return el.text()
        }
        return ""
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkEl = selectFirst("a[href]") ?: return null
        var href = linkEl.attr("href")
        if (href.startsWith("/")) href = mainUrl.trimEnd('/') + href

        val title = when {
            selectFirst("h2 a") != null -> selectFirst("h2 a")!!.text()
            selectFirst("h3 a") != null -> selectFirst("h3 a")!!.text()
            linkEl.attr("title").isNotEmpty() -> linkEl.attr("title")
            else -> linkEl.text().ifEmpty { firstText(".title",".name","h2","h3") }
        }

        val posterEl = selectFirst("img")
        var poster = posterEl?.attr("data-src") ?: posterEl?.attr("src") ?: ""
        if (poster.startsWith("/")) poster = mainUrl.trimEnd('/') + poster

        val type = TvType.Movie

        return MovieSearchResponse(
            title.ifEmpty { "Unknown" },
            href,
            this@Krx18.name,
            type,
            poster
        )
    }

    override suspend fun getMainPage(): HomePageResponse {
        val doc = app.get(mainUrl).document

        val containers = listOf(
            "div.latest-movies",
            "section.latest",
            "#main",
            "article",
            ".post",
            ".movie-list",
            ".items"
        )

        val found = mutableListOf<SearchResponse>()
        for (c in containers) {
            val els = doc.select(c)
            if (els.isNotEmpty()) {
                for (el in els) {
                    val items = el.select("article, .post, .item, .movie, .thumb, li")
                    if (items.isNotEmpty()) {
                        for (it in items) {
                            val r = try { it.toSearchResponse() } catch (e: Exception) { null }
                            if (r != null) found.add(r)
                        }
                        if (found.isNotEmpty()) break
                    }
                }
            }
            if (found.isNotEmpty()) break
        }

        if (found.isEmpty()) {
            val anchors = doc.select("a[href]")
            for (a in anchors) {
                val href = a.attr("href")
                if (href.contains("/watch") || href.contains("/movie") || href.contains("/title")) {
                    val fake = Element("div")
                    fake.appendChild(a.clone())
                    val r = try { fake.toSearchResponse() } catch (e: Exception) { null }
                    if (r != null) found.add(r)
                }
                if (found.size >= 40) break
            }
        }

        val pageList = HomePageList("Latest", found)
        return HomePageResponse(listOf(pageList))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(' ', '+')}"
        val doc = app.get(searchUrl).document
        val items = doc.select("article, .post, .item, .movie, .thumb, li").mapNotNull {
            try { it.toSearchResponse() } catch (e: Exception) { null }
        }
        if (items.isEmpty()) return getMainPage().homePage.first().items
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, h2, .title, .post-title")?.text() ?: "Unknown"
        var poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img")?.attr("src") ?: ""
        if (poster.startsWith("/")) poster = mainUrl.trimEnd('/') + poster

        val plot = doc.selectFirst(".description, .entry-content, .post-content, .desc")?.text() ?: ""

        val iframe = doc.selectFirst("iframe[src]")?.attr("src")
        val videoSource = doc.selectFirst("video source[src]")?.attr("src")

        val videoUrls = mutableListOf<String>()
        if (!videoSource.isNullOrEmpty()) videoUrls.add(videoSource)
        if (!iframe.isNullOrEmpty()) videoUrls.add(iframe)

        val candidates = doc.select("a[href]").mapNotNull { it.attr("href") }
        for (c in candidates) {
            if (c.contains("drive.google") || c.contains("vimeo") ||
                c.contains("youtube.com") || c.contains("mp4") || c.contains("m3u8")) {
                videoUrls.add(if (c.startsWith("/")) mainUrl.trimEnd('/') + c else c)
            }
        }

        val load = MovieLoadResponse(
            title,
            url,
            name,
            TvType.Movie,
            poster,
            Plot = plot
        )

        for (v in videoUrls.distinct()) {
            load.addVideo(Video(v))
        }

        if (load.playing.isEmpty()) {
            val dataSrc = doc.selectFirst("[data-src]")?.attr("data-src")
            if (!dataSrc.isNullOrEmpty()) load.addVideo(Video(dataSrc))
        }

        return load
    }

    override suspend fun loadLinks(url: String, redirects: Boolean, headers: Map<String, String>): List<String> {
        val doc = app.get(url).document
        val iframe = doc.selectFirst("iframe[src]")?.attr("src")
        val sources = mutableListOf<String>()
        if (!iframe.isNullOrEmpty()) sources.add(iframe)
        doc.select("a[href]").forEach {
            val h = it.attr("href")
            if (h.contains(".m3u8") || h.contains(".mp4") ||
                h.contains("googleusercontent") || h.contains("vimeo")) {
                sources.add(if (h.startsWith("/")) mainUrl.trimEnd('/') + h else h)
            }
        }
        return sources.distinct()
    }
}
