package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

    override val configKeyDomain = ConfigKey.Domain("kagane.to")
    private val apiUrl = "https://yuzuki.kagane.to"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )



    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true
        )

    private var genresCache: Set<MangaTag>? = null
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    // ---- Debug logging (grep logcat for "KAGANE_DBG") ----
    private fun dbg(msg: String) = println("KAGANE_DBG: $msg")

    private fun ByteArray.headHex(n: Int = 16): String =
        take(n).joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    private fun signedMessageType(bytes: ByteArray): String {
        // Widevine SignedMessage: field 1 (tag 0x08) = type varint
        if (bytes.size >= 2 && bytes[0].toInt() == 0x08) {
            return when (bytes[1].toInt() and 0xFF) {
                1 -> "LICENSE_REQUEST(1)"
                2 -> "LICENSE(2)"
                3 -> "ERROR_RESPONSE(3)"
                4 -> "SERVICE_CERTIFICATE_REQUEST(4)"
                5 -> "SERVICE_CERTIFICATE(5)"
                else -> "type=${bytes[1].toInt() and 0xFF}"
            }
        }
        return "unknown(${bytes.headHex(4)})"
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresCache ?: fetchGenres().also { genresCache = it }
        return MangaListFilterOptions(
            availableTags = genres,
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
        )
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        return try {
            val raw = callApi("$apiUrl/api/v2/genres/list", headers)
            val genres = runCatching { JSONArray(raw) }.getOrElse {
                val wrapper = runCatching { JSONObject(raw) }.getOrNull()
                wrapper?.optJSONArray("content")
                    ?: wrapper?.optJSONArray("genres")
                    ?: JSONArray()
            }
            buildSet {
                for (i in 0 until genres.length()) {
                    val item = genres.optJSONObject(i) ?: continue
                    val id = item.optString("genre_id").ifBlank { item.optString("id") }
                    val title = item.optString("genre_name")
                        .ifBlank { item.optString("genreName") }
                        .ifBlank { item.optString("name") }
                        .ifBlank { item.optString("title") }
                    if (id.isNotBlank() && title.isNotBlank() && UUID_REGEX.matches(id)) {
                        add(MangaTag(title, id, source))
                    }
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun parseContentRating(value: String?): ContentRating? {
        return when (value?.lowercase(Locale.ROOT)) {
            "safe" -> ContentRating.SAFE
            "suggestive" -> ContentRating.SUGGESTIVE
            "adult", "erotica", "pornographic" -> ContentRating.ADULT
            else -> null
        }
    }

    private fun copyCookiesToHost(oldDomain: String, newDomain: String, names: Array<String>) {
        val oldUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(oldDomain)
            .build()
        val cookies = context.cookieJar.loadForRequest(oldUrl)
            .filter { it.name in names }
        if (cookies.isEmpty()) return
        
        val newUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(newDomain)
            .build()
            
        val newCookies = cookies.map { c ->
            val builder = okhttp3.Cookie.Builder()
                .name(c.name)
                .value(c.value)
                .domain(newDomain)
                .path(c.path)
            if (c.secure) builder.secure()
            if (c.httpOnly) builder.httpOnly()
            builder.build()
        }
        context.cookieJar.saveFromResponse(newUrl, newCookies)
    }

    private suspend fun callApi(
        url: String,
        headers: okhttp3.Headers,
        jsonBody: JSONObject? = null
    ): String {
        val requestUrl = url.toHttpUrl()
        copyCookiesToHost(domain, requestUrl.host, arrayOf("cf_clearance", "__cf_bm"))
        
        val cleanClient = context.httpClient.newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
            
        val requestBuilder = okhttp3.Request.Builder()
            .url(requestUrl)
            .headers(headers)
            .tag(MangaSource::class.java, source)
            
        val mainDomainUrl = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .build()
        val cookies = context.cookieJar.loadForRequest(mainDomainUrl)
            .filter { it.name == "cf_clearance" || it.name == "__cf_bm" }
            
        if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            requestBuilder.addHeader("Cookie", cookieHeader)
        }
        
        if (jsonBody != null) {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            requestBuilder.post(jsonBody.toString().toRequestBody(mediaType))
            requestBuilder.addHeader("Content-Encoding", "identity")
        } else {
            requestBuilder.get()
        }
        
        val response = cleanClient.newCall(requestBuilder.build()).await()
        val responseBody = response.body?.string().orEmpty()
        
        if (!response.isSuccessful) {
            val code = response.code
            val isCloudflareOrForbidden = code == 403 || code == 503
            val isCfMsg = responseBody.contains("cloudflare", ignoreCase = true) || 
                          responseBody.contains("just a moment", ignoreCase = true) ||
                          responseBody.contains("cf-browser-verification", ignoreCase = true)
            
            if (isCloudflareOrForbidden || isCfMsg) {
                try {
                    context.requestBrowserAction(this, "https://$domain/")
                } catch (ea: UnsupportedOperationException) {
                    throw ParseException(
                        "Cloudflare verification is required. Please solve the check on the homepage.",
                        "https://$domain/",
                        Exception("HTTP $code: $responseBody")
                    )
                }
            }
            
            throw ParseException(
                "HTTP error $code: $responseBody",
                "https://$domain/",
                Exception("HTTP $code")
            )
        }
        
        return responseBody
    }

    private suspend fun getApiJson(
        url: String,
        headers: okhttp3.Headers,
        jsonBody: JSONObject? = null
    ): JSONObject {
        val responseBody = callApi(url, headers, jsonBody)
        return try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            val isCloudflare = responseBody.contains("cloudflare", ignoreCase = true) || 
                responseBody.contains("just a moment", ignoreCase = true) ||
                responseBody.contains("cf-browser-verification", ignoreCase = true) ||
                responseBody.contains("cf-chl-opt", ignoreCase = true)
            if (isCloudflare) {
                try {
                    context.requestBrowserAction(this, "https://$domain/")
                } catch (ea: UnsupportedOperationException) {
                    throw ParseException(
                        "Cloudflare verification is required. Please solve the check on the homepage.",
                        "https://$domain/",
                        e
                    )
                }
            }
            throw ParseException(
                "Invalid API response (possibly blocked by Cloudflare). Please open the website to verify.",
                "https://$domain/",
                e
            )
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated_at,desc"
            SortOrder.POPULARITY -> "total_views,desc"
            SortOrder.NEWEST -> "created_at,desc"
            SortOrder.ALPHABETICAL -> "series_name,asc"
            else -> "updated_at,desc"
        }

        val url = "$apiUrl/api/v2/search/series?page=${page - 1}&size=$pageSize&sort=$sortParam"
        val jsonBody = JSONObject()
        if (!filter.query.isNullOrEmpty()) {
            jsonBody.put("title", filter.query)
        }

        val genreIds = filter.tags.map { it.key }.filter { UUID_REGEX.matches(it) }
        if (genreIds.isNotEmpty()) {
            val genresArr = JSONArray()
            genreIds.forEach { genresArr.put(it) }
            val genresObj = JSONObject()
            genresObj.put("values", genresArr)
            genresObj.put("match_all", false)
            jsonBody.put("genres", genresObj)
        }

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val response = getApiJson(url, headers, jsonBody)

        val content = response.optJSONArray("content")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return emptyList()

        return (0 until content.length()).mapNotNull { i ->
            val item = content.getJSONObject(i)
            val id = item.optString("id").ifBlank { item.optString("series_id") }
            if (id.isBlank()) return@mapNotNull null
            val name = item.optString("name").ifBlank { item.optString("title") }.ifBlank { return@mapNotNull null }
            val src = item.optString("source").ifBlank { item.optString("source_name") }
            val title = if (src.isNotEmpty()) "$name [$src]" else name
            val coverImageId = item.optString("cover_image_id").ifBlank { item.optString("coverImageId") }
            val coverUrl = if (coverImageId.isNotBlank()) {
                "$apiUrl/api/v2/image/$coverImageId/compressed"
            } else {
                "$apiUrl/api/v2/series/$id/thumbnail"
            }

            Manga(
                id = generateUid(id),
                url = id,
                publicUrl = "https://$domain/series/$id",
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = parseContentRating(item.optString("content_rating"))
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url
        val url = "$apiUrl/api/v2/series/$seriesId"
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        val json = getApiJson(url, headers)

        val state = when (
            json.optString("publication_status")
                .ifBlank { json.optString("upload_status") }
                .ifBlank { json.optString("status") }
                .uppercase(Locale.ROOT)
        ) {
            "ONGOING" -> MangaState.ONGOING
            "COMPLETED", "ENDED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            "CANCELLED", "CANCELED", "DROPPED" -> MangaState.ABANDONED
            else -> null
        }

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is String -> {
                        if (UUID_REGEX.matches(item)) {
                            MangaTag(item, item, source)
                        } else {
                            null
                        }
                    }
                    is JSONObject -> {
                        val key = item.optString("genre_id").ifBlank { item.optString("id") }
                        val name = item.optString("genre_name")
                            .ifBlank { item.optString("genreName") }
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("title") }
                        if (key.isNotBlank() && name.isNotBlank()) {
                            MangaTag(name, key, source)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }.toSet()
        } ?: emptySet()

        val authors = linkedSetOf<String>()
        json.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> item.takeIf { it.isNotBlank() }?.let(authors::add)
                    is JSONObject -> item.optString("name")
                        .ifBlank { item.optString("title") }
                        .takeIf { it.isNotBlank() }
                        ?.let(authors::add)
                }
            }
        }
        if (authors.isEmpty()) {
            json.optJSONArray("series_staff")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val staff = arr.optJSONObject(i) ?: continue
                    val role = staff.optString("role")
                    if (role.equals("author", ignoreCase = true) || role.equals("artist", ignoreCase = true)) {
                        staff.optString("name").takeIf { it.isNotBlank() }?.let(authors::add)
                    }
                }
            }
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        fun parseChapters(content: JSONArray): List<MangaChapter> {
            val chapters = ArrayList<MangaChapter>(content.length())
            for (i in 0 until content.length()) {
                val ch = content.optJSONObject(i) ?: continue
                val chId = ch.optString("book_id")
                    .ifBlank { ch.optString("id") }
                    .ifBlank { ch.optString("bookId") }
                if (chId.isBlank()) continue
                val chapterNo = ch.optString("chapter_no")
                val chapterNumber = chapterNo.toChapterNumberOrNull()
                    ?: ch.optDouble("number", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                val sortNumber = ch.optDouble("sort_no", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                    ?: ch.optDouble("number_sort", ch.optDouble("numberSort", Double.NaN)).takeIf { !it.isNaN() }?.toFloat()
                val number = when {
                    sortNumber != null && chapterNumber != null && sortNumber >= chapterNumber -> sortNumber
                    sortNumber != null && chapterNumber == null -> sortNumber
                    chapterNumber != null -> chapterNumber
                    else -> 0f
                }
                val chTitle = ch.optString("title")
                    .ifBlank { ch.optString("name") }
                    .ifBlank { chapterNo.takeIf { it.isNotBlank() }?.let { "Chapter $it" }.orEmpty() }
                    .ifBlank { "Chapter $number" }
                val pageCount = ch.optInt("page_count", ch.optInt("pages_count", ch.optInt("pagesCount", 0)))
                val volume = ch.optString("volume_no")
                    .ifBlank { ch.optString("volume") }
                    .toIntOrNull() ?: 0
                val dateStr = ch.optString("published_on")
                    .ifBlank { ch.optString("release_date") }
                    .ifBlank { ch.optString("releaseDate") }
                    .ifBlank { ch.optString("created_at") }
                    .substringBefore('T')

                val groupsArr = ch.optJSONArray("groups")
                val groupNames = if (groupsArr != null && groupsArr.length() > 0) {
                    buildList {
                        for (j in 0 until groupsArr.length()) {
                            val g = groupsArr.optJSONObject(j) ?: continue
                            val gName = g.optString("title").ifBlank { g.optString("name") }
                            if (gName.isNotBlank()) {
                                add(gName)
                            }
                        }
                    }.joinToString(" & ").takeIf { it.isNotBlank() }
                } else {
                    null
                }

                chapters.add(
                    MangaChapter(
                        id = generateUid("$seriesId:$chId"),
                        title = chTitle,
                        number = number,
                        volume = volume,
                        url = "/series/$seriesId/$chId?pages=$pageCount",
                        uploadDate = try { dateFormat.parse(dateStr)?.time ?: 0L } catch (_: Exception) { 0L },
                        source = source,
                        scanlator = groupNames,
                        branch = groupNames,
                    ),
                )
            }
            return chapters.distinctBy { Triple(it.branch, it.volume, it.number) }
                .sortedWith(
                    compareBy<MangaChapter> { it.number <= 0f }
                        .thenBy { it.number }
                        .thenBy { it.volume }
                        .thenBy { it.title.orEmpty() },
                )
        }

        var chapters = parseChapters(
            json.optJSONArray("series_books")
                ?: json.optJSONArray("seriesBooks")
                ?: json.optJSONArray("books")
                ?: json.optJSONArray("content")
                ?: JSONArray(),
        )
        if (chapters.isEmpty()) {
            val chaptersUrl = "$apiUrl/api/v2/series/$seriesId/books/list"
            val chapterResp = getApiJson(chaptersUrl, headers)
            chapters = parseChapters(
                chapterResp.optJSONArray("series_books")
                    ?: chapterResp.optJSONArray("seriesBooks")
                    ?: chapterResp.optJSONArray("content")
                    ?: JSONArray(),
            )
        }

        return manga.copy(
            description = json.optString("description").ifBlank { json.optString("summary") }.ifBlank { null },
            state = state,
            authors = authors,
            tags = genres,
            chapters = chapters,
            contentRating = parseContentRating(json.optString("content_rating")) ?: manga.contentRating,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        // Disable related/suggested manga feature
        return emptyList()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val uri = URI(chapter.url)
        val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 3) throw Exception("Invalid chapter URL format: ${chapter.url}")

        val seriesId = pathParts[1]
        val chapterId = pathParts[2]

        val challengeResp = getChallengeResponse(chapterId)
        val token = challengeResp.accessToken
        val currentCacheUrl = challengeResp.cacheUrl
        copyCookiesToHost(domain, currentCacheUrl.toHttpUrl().host, arrayOf("cf_clearance", "__cf_bm"))

        val pages = challengeResp.pages.map { page ->
            val imageUrl = "$currentCacheUrl/api/v2/books/page/$chapterId/${page.pageUuid}.${page.ext}?token=$token&is_datasaver=false"
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
        if (pages.isEmpty()) throw Exception("No pages returned from API")
        return pages
    }

    private class ChallengeResponse(
        val accessToken: String,
        val cacheUrl: String,
        val pages: List<ManifestPage>
    )

    private class ManifestPage(
        val pageNumber: Int,
        val pageUuid: String,
        val ext: String
    )

    private var integrityToken: String = ""
    private var integrityTokenExp: Long = 0L

    private suspend fun getChallengeResponse(chapterId: String): ChallengeResponse {
        val integrityToken = getIntegrityToken()

        val challengeUrl = "$apiUrl/api/v2/books/$chapterId?is_datasaver=false"
        val jsonBody = JSONObject()

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .add("x-integrity-token", integrityToken)
            .build()

        val responseBody = getApiJson(challengeUrl, headers, jsonBody)

        val token = responseBody.optString("access_token").ifBlank {
            responseBody.optString("accessToken")
        }.ifBlank {
            throw Exception("Invalid token response: missing access token")
        }

        val cacheUrl = responseBody.optString("cache_url").ifBlank {
            responseBody.optString("cacheUrl")
        }.ifBlank {
            throw Exception("Invalid token response: missing cache url")
        }

        val manifestObj = responseBody.optJSONObject("manifest")
        val pagesJson = manifestObj?.optJSONArray("pages")
        val manifestPages = mutableListOf<ManifestPage>()

        if (pagesJson != null) {
            for (i in 0 until pagesJson.length()) {
                val pageObj = pagesJson.optJSONObject(i) ?: continue
                val pageNo = pageObj.optInt("page_no", i + 1)
                val pageUuid = pageObj.optString("page_id", "")
                val ext = pageObj.optString("ext", "jxl")
                if (pageUuid.isNotEmpty()) {
                    manifestPages.add(ManifestPage(pageNo, pageUuid, ext))
                }
            }
        } else {
            val mappingJson = responseBody.optJSONObject("page_mapping")
                ?: responseBody.optJSONObject("pageMapping")
                ?: responseBody.optJSONObject("file_mapping")
                ?: responseBody.optJSONObject("fileMapping")
                ?: responseBody.optJSONObject("files")
            if (mappingJson != null) {
                val keys = mappingJson.keys().asSequence().toList().mapNotNull { it.toIntOrNull() }.sorted()
                keys.forEach { pageNo ->
                    val pageUuid = mappingJson.optString(pageNo.toString()).toPageFileId()
                    if (pageUuid.isNotEmpty()) {
                        manifestPages.add(ManifestPage(pageNo, pageUuid, "jxl"))
                    }
                }
            }
        }

        return ChallengeResponse(token, cacheUrl, manifestPages)
    }

    private suspend fun getIntegrityToken(): String {
        val now = System.currentTimeMillis()
        if (integrityToken.isNotBlank() && now < integrityTokenExp) {
            return integrityToken
        }

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        runCatching { webClient.httpGet("https://$domain/", headers).close() }

        val integrityUrl = urlBuilder().addPathSegments("api/integrity").build().toString()
        val response = getApiJson(integrityUrl, headers, JSONObject())

        val token = response.optString("token")
        if (token.isBlank()) {
            throw Exception("Failed to retrieve integrity token")
        }
        integrityToken = token
        integrityTokenExp = response.optLong("exp", 0L) * 1000L
        return integrityToken
    }

    private fun Any?.toPageFileId(): String = when (this) {
        is String -> toFileNamePart()
        is JSONObject -> optFirstString(
            "pageUuid",
            "page_uuid",
            "pageId",
            "page_id",
            "fileId",
            "file_id",
            "fileName",
            "file_name",
            "filename",
            "name",
            "id",
            "path",
            "url",
        ).toFileNamePart()
        else -> ""
    }

    private fun JSONObject.optFirstString(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun String.toFileNamePart(): String = substringBefore('?').substringAfterLast('/').trim()


    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url
        
        // Remove encoding headers from requests to avoid gzip compression issues
        val request = if (originalRequest.method == "GET" || originalRequest.method == "POST") {
            originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Accept-Encoding")
                .build()
        } else {
            originalRequest
        }

        if (url.queryParameterNames.contains("token")) {
            val response = chain.proceed(request)
            if (response.code == 401 || response.code == 403 || response.code == 507) {
                response.close()

                val pathSegments = url.pathSegments
                val booksIndex = pathSegments.indexOf("books")
                val chapterId = if (booksIndex != -1 && booksIndex + 2 < pathSegments.size) {
                    pathSegments[booksIndex + 2]
                } else {
                    null
                }

                if (chapterId != null) {
                    dbg("Token expired for chapter $chapterId, refreshing...")
                    val challenge = kotlinx.coroutines.runBlocking {
                        try {
                            getChallengeResponse(chapterId)
                        } catch (e: Exception) {
                            dbg("Failed to refresh token: ${e.message}")
                            null
                        }
                    }
                    if (challenge != null) {
                        val newUrl = url.newBuilder()
                            .host(URI(challenge.cacheUrl).host)
                            .setQueryParameter("token", challenge.accessToken)
                            .build()
                        return chain.proceed(request.newBuilder().url(newUrl).build())
                    }
                }
            }
            return response
        }
        return chain.proceed(request)
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: okhttp3.HttpUrl): Manga? {
        val seriesId = link.pathSegments.getOrNull(1) ?: return null
        val seed = Manga(
            id = generateUid(seriesId),
            title = "",
            altTitles = emptySet(),
            url = seriesId,
            publicUrl = link.toString(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
        return getDetails(seed)
    }

    private fun String.toChapterNumberOrNull(): Float? = trim()
        .replace(',', '.')
        .toFloatOrNull()
}
