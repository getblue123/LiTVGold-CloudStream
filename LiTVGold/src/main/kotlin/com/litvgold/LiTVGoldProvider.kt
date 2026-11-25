package com.litvgold

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.util.UUID
import org.json.JSONObject
import org.jsoup.Jsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class LiTVGoldProvider : MainAPI() {
    override var mainUrl = "https://www.litv.tv"
    override var name = "LiTV 金光布袋戲 (實驗版)"
    override var lang = "zh"
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val mainPage = mainPageOf("live" to "金光直播", "vod" to "金光劇集")
    private val liveUrl = "$mainUrl/channel/watch/litv-vchannel163"
    private val liveName = "金光布袋戲台 163"
    private data class Show(val title: String, val id: String, val count: Int)
    private val shows = listOf(
        Show("黑白龍狼傳", "VOD00122791", 27),
        Show("天地風雲錄之決戰時刻", "VOD00122792", 20),
        Show("天地風雲錄之九龍變", "VOD00122793", 36),
        Show("天地風雲錄之劍影魔蹤", "VOD00123267", 20),
        Show("天地風雲錄之魔戮血戰", "VOD00123288", 34),
        Show("金光御九界之墨武俠鋒", "VOD00124033", 32),
        Show("金光御九界之墨世佛劫", "VOD00124034", 32),
        Show("金光御九界之墨邪錄", "VOD00124035", 22),
        Show("金光御九界之東皇戰影", "VOD00124036", 40),
        Show("金光御九界之魆妖紀", "VOD00124037", 32),
        Show("金光御九界之鬼途奇行錄", "VOD00132406", 32),
        Show("金光御九界之齊神籙", "VOD00150383", 32),
        Show("金光御九界之戰血天道", "VOD00194011", 32),
        Show("金光御九界之仙古狂濤", "VOD00261761", 32)
    )
    private fun page(show: Show) = "$mainUrl/drama/watch/${show.id}"
    private fun liveCard() = newLiveSearchResponse(liveName, liveUrl)
    private fun card(show: Show) = newTvSeriesSearchResponse(show.title, page(show)) { episodes = show.count }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = if (page != 1) emptyList() else if (request.data == "live") listOf(liveCard()) else shows.map { card(it) }
        return newHomePageResponse(request.name, items, hasNext = false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        val all = q.isEmpty() || q in setOf("金光", "布袋戲", "金光布袋戲", "LiTV")
        return buildList {
            if (all || liveName.contains(q, true) || q == "直播") add(liveCard())
            shows.filter { all || it.title.contains(q, true) }.forEach { add(card(it)) }
        }
    }
    private fun officialPage(url: String): Boolean {
        val u = try { URI(url) } catch (_: Exception) { return false }
        return u.scheme == "https" && u.host == "www.litv.tv" && u.userInfo == null && u.port == -1 &&
            u.rawQuery == null && u.fragment == null &&
            (u.path == "/channel/watch/litv-vchannel163" || Regex("^/drama/watch/VOD[0-9]+/?$").matches(u.path))
    }
    private fun pageProps(html: String): JSONObject? {
        val data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")
    }
    override suspend fun load(url: String): LoadResponse? {
        if (url == liveUrl) return newLiveStreamLoadResponse(liveName, url, url) {
            plot = "LiTV 官方直播；依服務端權限及所在地區提供播放。"
        }
        val show = shows.firstOrNull { page(it) == url } ?: return null
        val html = app.get(url).text
        val doc = Jsoup.parse(html, url)
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("https://") }
        val byNumber = sortedMapOf<Int, String>()
        val pattern = Regex("^(?:第\\s*)?(\\d{1,3})(?:\\s*集)?$")
        for (a in doc.select("a[href]")) {
            val number = pattern.matchEntire(a.text().trim())?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (number !in 1..show.count) continue
            val target = try { URI(url).resolve(a.attr("href")).toString() } catch (_: Exception) { continue }
            if (!officialPage(target) || !target.contains("/drama/watch/")) continue
            byNumber.putIfAbsent(number, target)
        }
        val seasons = pageProps(html)?.optJSONObject("seriesTree")?.optJSONArray("seasons")
        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val entries = seasons.optJSONObject(i)?.optJSONArray("episodes") ?: continue
                for (j in 0 until entries.length()) {
                    val e = entries.optJSONObject(j) ?: continue
                    val id = e.optString("content_id")
                    val n = e.optString("episode").toIntOrNull() ?: continue
                    val target = "$mainUrl/drama/watch/$id"
                    if (n in 1..show.count && officialPage(target)) byNumber.putIfAbsent(n, target)
                }
            }
        }
        val episodes = byNumber.map { (n, target) ->
            newEpisode(target) { episode = n; name = "第${n}集"; posterUrl = poster }
        }
        return newTvSeriesLoadResponse(show.title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = "官方頁面：$url\n目前讀取 ${episodes.size}/${show.count} 集；播放依 LiTV 權限。"
        }
    }
    private val guestId = UUID.randomUUID().toString()
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (!officialPage(data)) return false
        val info = pageProps(app.get(data).text)?.optJSONObject("programInformation")
            ?: throw ErrorLoadingException("LiTV 網頁資料無法讀取")
        val isLive = data == liveUrl
        val assetId = if (isLive) info.optString("content_id") else info.optJSONArray("assets")?.optJSONObject(0)?.optString("asset_id").orEmpty()
        val mediaType = if (isLive) info.optString("content_type") else "vod"
        if (assetId.isBlank() || mediaType.isBlank()) throw ErrorLoadingException("LiTV 播放識別碼無法讀取")
        val body = JSONObject().put("AssetId", assetId).put("MediaType", mediaType).put("puid", guestId)
        val response = app.post("$mainUrl/api/get-urls-no-auth", requestBody = body.toString().toRequestBody("application/json".toMediaType())).text
        val result = JSONObject(response)
        val error = result.optJSONObject("error")
        if (error != null) {
            val message = error.optString("message")
            throw ErrorLoadingException(if (message.contains("OutsideRegionError")) "LiTV 僅限台灣地區" else "LiTV 拒絕播放，請使用官方平台確認權限")
        }
        val urls = result.optJSONObject("result")?.optJSONArray("AssetURLs") ?: throw ErrorLoadingException("LiTV 未回傳播放清單")
        var found = false
        for (i in 0 until urls.length()) {
            val stream = urls.optString(i)
            val uri = try { URI(stream) } catch (_: Exception) { continue }
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) continue
            val headersForHls = mapOf("Accept-Encoding" to "identity")
            val manifest = app.get(stream, headers = headersForHls).text
            if (!manifest.trimStart().startsWith("#EXTM3U")) continue
            if (manifest.contains("KEYFORMAT=\"", true) && Regex("KEYFORMAT=\"(?!identity\")", RegexOption.IGNORE_CASE).containsMatchIn(manifest)) continue
            callback(newExtractorLink(name, "LiTV HLS", stream, ExtractorLinkType.M3U8) { headers = headersForHls })
            found = true
        }
        if (!found) throw ErrorLoadingException("LiTV 未取得相容 HLS，請確認官方平台播放權限")
        return true
    }
}
