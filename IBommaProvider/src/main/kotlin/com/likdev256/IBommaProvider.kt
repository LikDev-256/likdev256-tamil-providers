package com.likdev256

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import java.net.URLEncoder

class IBommaProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://ww2.ibomma.cx/telugu-movies"
    override var name = "IBomma"
    override val hasMainPage = true
    override var lang = "te"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries
    )

    //pages
    //"#content > div > article" //Latest
    //"#content > article:lt(6)" //web series
    //"#content > article:gt(5):lt(6)" //foreign dub
    //"#content > article:gt(11)" //addon

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        val pageSelectors = listOf(
            Pair("Latest", "#content > div > article"),
            Pair("Movies", "#content > article:nth-child(-n+13)"),
        )
        val pages = pageSelectors.apmap { (title, selector) ->
            val list = document.select(selector).mapNotNull {
                    it.toSearchResult()
                }
            HomePageList(title, list)
        }
        return HomePageResponse(pages)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href").toString())
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = app.get(mainUrl).document.select(".mob-search form").attr("action")
        fun String.encodeUri() = URLEncoder.encode(this, "utf8")
        val document = app.get("$searchUrl?label=telugu&q=${query.encodeUri()}").document
        val scriptData = document.select("script").find { it.data().contains("data=") }?.data()
            ?.substringAfter("data= ")?.substringBefore("</script>") ?: return null
        val response = parseJson<Response>(scriptData)
        return response.hits?.hitslist?.map {
            val title = it.source?.title?.substringBefore("Movie") ?: return null
            val posterUrl = it.source?.imageLink ?: return null
            val href = it.source?.location ?: return null
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        val document = app.get(url).document
        val title = document.selectFirst(".entry-title-movie")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".single-poster img")?.attr("src"))
        val year = document.select(".entry-tags-movies span").text().trim().toIntOrNull()
        val tvType =
            if (document.select("#eplist").isNullOrEmpty()) TvType.Movie else TvType.TvSeries
        val description =
            document.selectFirst(".additional-info")?.text()?.trim()!!.replace("Synopsis: ", "")
        val trailer = fixUrlNull(document.select(".button-trailer a").attr("src"))
        //val rating = document.select("div.gmr-meta-rating > span:nth-child(3)").text().toRatingInt()
        val actors =
            document.select("div.clearfix.content-moviedata > div:nth-child(7) a").map { it.text() }

        return if (tvType == TvType.TvSeries) {
            val episodeUrls = getUrls(url) ?: return null
            val episodes = document.select("#eplist tr").mapNotNull { res ->
                val name = res.select("b").text().trim()
                //val season = name.substringAfter("S").substringBefore(' ').toInt()
                val episode = res.select("button").text().filter { it.isDigit() }.toInt()
                val href = episodeUrls[episode]
                Episode(
                    data = href, name = name, episode = episode
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                rating
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                //this.tags = tags
                rating
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val urls: List<String> = if (data.startsWith(mainUrl.substringBeforeLast("/"))) {
            app.get(data).document.selectFirst("#main > script:nth-child(6)")?.data()
                ?.substringAfter("const urls = [")?.substringBefore("]")?.trim()
                ?.replace(",'',", "")?.split(",")?.map { it -> it.trim() } ?: return false
        } else {
            listOf(data)
        }
        urls.forEach { url ->
            val domainUrl = url.substringAfter("'").substringBefore("/player")
            val document = app.get(
                url = url.replace("'", ""), referer = mainUrl.substringBeforeLast("/")
            ).document
            document.select("body script").mapNotNull {
                val srcRegex = Regex("""(file:")(https?.*?\.mp4)""")
                val source =
                    srcRegex.find(it.select("script").toString())?.groupValues?.getOrNull(2)
                        ?.toString()
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        source.toString(),
                        referer = domainUrl,
                        quality = Qualities.Unknown.value,
                    )
                )
            }
        }
        return true
    }

    private suspend fun getUrls(url: String): List<String>? {

        return app.get(url).document.selectFirst("#ib-4-f > script:nth-child(4)")?.data()
            ?.substringAfter("const urls = [")?.substringBefore("]")?.trim()?.replace(",'',", "")
            ?.split(",")?.toList()
    }

    data class Response(
        @JsonProperty("hits") var hits: Hits? = Hits()
    )

    data class Hits(
        @JsonProperty("hits") var hitslist: ArrayList<HitsList> = arrayListOf()
    )

    data class HitsList(
        @JsonProperty("_source") var source: Source? = Source()
    )

    data class Source(
        @JsonProperty("location") var location: String? = null,
        @JsonProperty("title") var title: String? = null,
        @JsonProperty("image_link") var imageLink: String? = null,
    )
}