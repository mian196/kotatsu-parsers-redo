package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("RYZUKOMIK", "Ryzukomik", "id")
internal class Ryzukomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RYZUKOMIK, 20) {

	override val configKeyDomain = ConfigKey.Domain("ryzukomik.my.id")

	private val apiDomain = "kizoy.serv00.net"
	private val apiBase = "https://$apiDomain/yu.php"

	private fun buildHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("Accept", "application/json, text/plain, */*")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = buildGenreList(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val apiUrl = buildString {
			append(apiBase)
			
			if (!filter.query.isNullOrEmpty()) {
				append("?s=").append(filter.query!!.urlEncoded()).append("&page=").append(page)
			} else {
				val tag = filter.tags.oneOrThrowIfMany()
				if (tag != null) {
					append("?genre=").append(tag.key.urlEncoded()).append("&page=").append(page)
				} else {
					append("?latest=1&page=").append(page)
				}
			}
		}

		val json = webClient.httpGet(apiUrl, buildHeaders()).parseJson()

		if (!json.optBoolean("status", false)) return emptyList()
		val data = json.optJSONObject("data") ?: return emptyList()
		val komikArr = data.optJSONArray("komik") ?: return emptyList()

		return (0 until komikArr.length()).mapNotNull { i ->
			val obj = komikArr.getJSONObject(i)
			parseMangaFromListItem(obj)
		}
	}

	private fun parseMangaFromListItem(obj: JSONObject): Manga? {
		val rawLink = obj.optString("link").ifBlank { return null }
		val slug = rawLink.trimEnd('/').substringAfterLast('/')
		if (slug.isBlank()) return null

		val url = "/manga/$slug"
		val title = obj.optString("judul").ifBlank { return null }
		val cover = obj.optString("gambar").ifBlank { null }
		val tipe = obj.optString("tipe").lowercase(Locale.ROOT)

		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = "https://$domain$url/",
			title = title.replace("Komik", "").trim(),
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null, // Set based on the site's overall content
			coverUrl = cover,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val apiUrl = "$apiBase?komik=$slug"

		val json = webClient.httpGet(apiUrl, buildHeaders()).parseJson()

		if (!json.optBoolean("status", false)) {
			return manga
		}
		val data = json.optJSONObject("data") ?: return manga
		val detail = data.optJSONObject("detail")
		val statusText = detail?.optString("status").orEmpty()
		val state = when {
			statusText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("completed", ignoreCase = true) ||
				statusText.contains("tamat", ignoreCase = true) ||
				statusText.contains("selesai", ignoreCase = true) -> MangaState.FINISHED
			else -> null
		}

		val pengarang = detail?.optString("pengarang").orEmpty().trim()
		val ilustrator = detail?.optString("ilustrator").orEmpty().trim()
		val authors = setOfNotNull(
			pengarang.ifBlank { null },
			ilustrator.takeIf { it.isNotBlank() && it != pengarang },
		)

		val altJudul = detail?.optString("judul_alternatif").orEmpty().trim()
		val altTitles = if (altJudul.isNotBlank() && altJudul != "-") setOf(altJudul) else emptySet()
		val jenis = detail?.optString("jenis_komik").orEmpty().trim()
		val genreArr = data.optJSONArray("genre")
		val tags = buildSet {
			if (genreArr != null) {
				for (i in 0 until genreArr.length()) {
					val g = genreArr.getJSONObject(i)
					val nama = g.optString("nama").trim()
					val link = g.optString("link").trim()
					if (nama.isNotBlank()) {
						add(MangaTag(
							key = link.ifBlank { nama.lowercase(Locale.ROOT) },
							title = nama,
							source = source,
						))
					}
				}
			}
		}

		val description = data.optString("desk").trim().ifBlank { null }
		val cover = data.optString("gambar").ifBlank { manga.coverUrl }
		val chapterArr = data.optJSONArray("daftar_chapter")
		val chapters = parseChapterList(chapterArr, slug)

		return manga.copy(
			title = data.optString("judul").replace("Komik", "").trim().ifBlank { manga.title },
			altTitles = altTitles,
			description = description,
			state = state,
			authors = authors,
			contentRating = ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = cover,
		)
	}

	private fun parseChapterList(arr: JSONArray?, mangaSlug: String): List<MangaChapter> {
		if (arr == null) return emptyList()

		return (0 until arr.length()).mapNotNull { i ->
			val obj = arr.getJSONObject(i)
			val rawLink = obj.optString("link_chapter").ifBlank { return@mapNotNull null }
			val chapterSlug = rawLink.trimEnd('/').substringAfterLast('/')
			if (chapterSlug.isBlank()) return@mapNotNull null

			val chapterUrl = "/chapter/$chapterSlug"
			val title = obj.optString("judul_chapter").trim().ifBlank { chapterSlug }
			val dateText = obj.optString("waktu_rilis").trim()
			val number = Regex("""(\d+(?:\.\d+)?)""").find(title)?.value?.toFloatOrNull()
				?: (arr.length() - i).toFloat()

			MangaChapter(
				id = generateUid(chapterUrl),
				title = title,
				url = chapterUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = parseRelativeDate(dateText),
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.trimEnd('/').substringAfterLast('/')
		val apiUrl = "$apiBase?chapter=$slug"

		val json = webClient.httpGet(apiUrl, buildHeaders()).parseJson()

		if (!json.optBoolean("status", false)) return emptyList()
		val data = json.optJSONObject("data") ?: return emptyList()
		val gambarArr = data.optJSONArray("gambar") ?: return emptyList()

		return (0 until gambarArr.length()).mapNotNull { i ->
			val obj = gambarArr.getJSONObject(i)
			val url = obj.optString("url").ifBlank { return@mapNotNull null }
			MangaPage(
				id = generateUid("$i-$slug"),
				url = url, // URL gambar langsung
				preview = null,
				source = source,
			)
		}
	}


	private fun parseRelativeDate(text: String): Long {
		if (text.isBlank()) return 0L
		val lower = text.lowercase(Locale.ROOT)
		val num = Regex("""(\d+)""").find(lower)?.value?.toLongOrNull() ?: 1L
		val now = System.currentTimeMillis()
		return when {
			lower.contains("menit") -> now - num * 60_000L
			lower.contains("jam") -> now - num * 3_600_000L
			lower.contains("hari") || lower.contains("day") -> now - num * 86_400_000L
			lower.contains("minggu") || lower.contains("week") -> now - num * 604_800_000L
			lower.contains("bulan") || lower.contains("month") -> now - num * 2_592_000_000L
			lower.contains("tahun") || lower.contains("year") -> now - num * 31_536_000_000L
			else -> 0L
		}
	}

	private fun buildGenreList(): Set<MangaTag> {
		val genres = listOf(
			"action" to "Action",
			"adventure" to "Adventure",
			"comedy" to "Comedy",
			"crime" to "Crime",
			"drama" to "Drama",
			"fantasy" to "Fantasy",
			"girls-love" to "Girls' Love",
			"harem" to "Harem",
			"historical" to "Historical",
			"horror" to "Horror",
			"isekai" to "Isekai",
			"magical-girls" to "Magical Girls",
			"mecha" to "Mecha",
			"medical" to "Medical",
			"music" to "Music",
			"mystery" to "Mystery",
			"philosophical" to "Philosophical",
			"psychological" to "Psychological",
			"romance" to "Romance",
			"sci-fi" to "Sci-Fi",
			"shoujo-ai" to "Shoujo Ai",
			"shounen-ai" to "Shounen Ai",
			"slice-of-life" to "Slice of Life",
			"sports" to "Sports",
			"superhero" to "Superhero",
			"thriller" to "Thriller",
			"tragedy" to "Tragedy",
			"wuxia" to "Wuxia",
			"yuri" to "Yuri",
		)
		return genres.map { (key, title) ->
			MangaTag(key = key, title = title, source = source)
		}.toSet()
	}
}
