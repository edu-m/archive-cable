package tv.archivecable

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Internet Archive client: advanced search + per-item file listings. */
object ArchiveApi {
    const val UA = "ArchiveCable/1.0 (+https://github.com/edu-m/archive-cable)"

    data class Hit(val id: String, val title: String, val year: String?, val description: String?, val language: String?)

    fun downloadUrl(itemId: String, file: String): String =
        "https://archive.org/download/${enc(itemId)}/" + file.split('/').joinToString("/") { enc(it) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun get(url: String): String {
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "application/json")
                try {
                    val code = c.responseCode
                    if (code != 200) throw IOException("HTTP $code for $url")
                    return c.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                last = e
                Thread.sleep(800L * (attempt + 1))
            }
        }
        throw last ?: IOException("request failed: $url")
    }

    fun search(query: String, rows: Int): List<Hit> {
        val params = listOf(
            "q" to query,
            "fl[]" to "identifier", "fl[]" to "title", "fl[]" to "year", "fl[]" to "description", "fl[]" to "language",
            "rows" to rows.toString(), "page" to "1",
            "sort[]" to "downloads desc",
            "output" to "json",
        ).joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val docs = JSONObject(get("https://archive.org/advancedsearch.php?$params"))
            .getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier").ifEmpty { return@mapNotNull null }
            Hit(id, stringOf(d, "title") ?: id, stringOf(d, "year"), stringOf(d, "description")?.let(::cleanText), stringOf(d, "language"))
        }
    }

    /** Playable video files of an item, one per episode, best rendition picked. */
    fun programs(hit: Hit, minMs: Long, maxMs: Long, maxPerItem: Int, reject: Regex? = null): List<Program> {
        val files = JSONObject(get("https://archive.org/metadata/${enc(hit.id)}/files")).optJSONArray("result")
            ?: return emptyList()
        data class Cand(val rank: Int, val name: String, val title: String?, val ms: Long)
        val groups = LinkedHashMap<String, Cand>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val rank = FORMAT_RANK[f.optString("format")] ?: continue
            val name = f.optString("name").ifEmpty { continue }
            // Some items carry several language versions (e.g. "… (Arabic).mp4"): skip the unwanted ones.
            if (reject != null && reject.containsMatchIn(name)) continue
            val ms = parseLength(f.optString("length"))
            if (ms <= 0) continue
            val key = f.optString("original").ifEmpty { name }
            val prev = groups[key]
            if (prev == null || prev.rank < rank) groups[key] = Cand(rank, name, f.optString("title").ifEmpty { null }, ms)
        }
        // The same video sometimes appears under two "originals" (e.g. .mkv and .mp4); dedupe by length.
        val seen = HashSet<Long>()
        val cands = groups.values
            .filter { it.ms in minMs..maxMs }
            .sortedBy { it.name }
            .filter { seen.add(it.ms / 2000) }
        val multi = cands.size > 1
        val picked = if (cands.size > maxPerItem) {
            val step = cands.size.toDouble() / maxPerItem
            (0 until maxPerItem).map { cands[(it * step).toInt()] }
        } else cands
        return picked.map { c ->
            Program(
                itemId = hit.id,
                title = hit.title.trim(),
                episode = if (multi) (c.title ?: episodeName(c.name)) else null,
                file = c.name,
                durationMs = c.ms,
                year = hit.year,
                description = hit.description,
            )
        }
    }

    private val FORMAT_RANK = mapOf("h.264" to 4, "h.264 IA" to 3, "MPEG4" to 3, "512Kb MPEG4" to 1)

    private fun stringOf(o: JSONObject, key: String): String? = when (val v = o.opt(key)) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }.ifBlank { null }
        else -> v.toString().ifBlank { null }
    }

    /** "1653.22" or "27:33" or "1:02:03" → milliseconds. */
    fun parseLength(s: String): Long {
        if (s.isBlank()) return 0
        return try {
            if (':' in s) {
                s.split(':').fold(0.0) { acc, part -> acc * 60 + part.toDouble() }.times(1000).toLong()
            } else (s.toDouble() * 1000).toLong()
        } catch (_: NumberFormatException) {
            0
        }
    }

    private fun episodeName(file: String): String =
        file.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("_512kb$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_.]+"), " ")
            .trim()

    fun cleanText(raw: String): String =
        raw.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > 600) it.take(600).substringBeforeLast(' ') + "…" else it }
}
