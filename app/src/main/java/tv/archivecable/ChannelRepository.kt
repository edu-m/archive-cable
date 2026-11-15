package tv.archivecable

import android.content.Context
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Loads channel lineups from the Internet Archive, caching them on disk for the week. */
class ChannelRepository(
    private val context: Context,
    val language: String,
    val decades: DecadeFilter = DecadeFilter.EVERYTHING,
) {

    /** EMPTY: loaded fine, but nothing matches the selected decades. */
    enum class Status { LOADING, READY, FAILED, EMPTY }

    val config: LineupConfig = parseConfig(context.assets.open("channels_$language.json").bufferedReader().use { it.readText() })
    val channels: List<ChannelDef> = config.channels.sortedBy { it.number }

    private val schedules = ConcurrentHashMap<String, Schedule>()
    private val statuses = ConcurrentHashMap<String, Status>()
    @Volatile var filler: List<Program> = emptyList()
        private set

    /** Called on the main thread whenever a lineup finishes loading. */
    var onChanged: ((channelId: String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestPermits = Semaphore(8)
    private val channelPermits = Semaphore(3)
    private val cacheDir = File(context.filesDir, "lineups/$language").apply { mkdirs() }

    fun schedule(id: String): Schedule? = schedules[id]
    fun status(id: String): Status = statuses[id] ?: Status.LOADING

    fun start(priorityId: String?) {
        scope.launch { pruneCache() }
        val first = channels.firstOrNull { it.id == priorityId } ?: channels.firstOrNull() ?: return
        scope.launch {
            // The channel being watched gets the network to itself for a head start.
            val firstJob = launch { loadChannel(first) }
            withTimeoutOrNull(12_000) { firstJob.join() } // waits without cancelling the load
            launch { loadFiller() }
            for (ch in channels) if (ch.id != first.id) launch { channelPermits.withPermit { loadChannel(ch) } }
        }
    }

    /** Ask for a channel next (e.g. the viewer just tuned to it). */
    fun prioritize(id: String) {
        val ch = channels.firstOrNull { it.id == id } ?: return
        if (schedules.containsKey(id) || !prioritized.add(id)) return
        scope.launch { loadChannel(ch) }
    }

    private val prioritized = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val loading = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private suspend fun loadChannel(ch: ChannelDef) {
        if (schedules.containsKey(ch.id) || !loading.add(ch.id)) return
        try {
            loadChannelInner(ch)
        } finally {
            loading.remove(ch.id)
        }
    }

    private suspend fun loadChannelInner(ch: ChannelDef) {
        val signature = "${ch.query}|${config.globalExclude}|${config.filterSignature}|${ch.minSeconds}|${ch.maxSeconds}|${ch.sample}|${decades.signature}|v3"
        val job = LineupJob(ch.id, signature, ch.query, ch.sample, ch.minSeconds * 1000L, ch.maxSeconds * 1000L, maxPerItem = 6, target = 24, decades)
        val programs = obtain(job) { statuses[ch.id] = Status.FAILED; notify(ch.id) }
        schedules[ch.id] = Schedule(ch, programs)
        statuses[ch.id] = if (programs.isEmpty()) Status.EMPTY else Status.READY
        notify(ch.id)
    }

    private suspend fun loadFiller() {
        val f = config.filler
        fun job(d: DecadeFilter) = LineupJob("_filler", "${f.query}|${config.globalExclude}|${config.filterSignature}|${f.sample}|${f.minSeconds}|${d.signature}|v3",
            f.query, f.sample, f.minSeconds * 1000L, 4 * 3600_000L, maxPerItem = 12, target = 30, d)
        // Commercials follow the selected decades too, falling back to any era rather than none.
        var programs = obtain(job(decades)) {}
        if (programs.size < 5 && !decades.isAll) programs = obtain(job(DecadeFilter.EVERYTHING)) {}
        filler = programs.sortedBy { it.url }
        notify("_filler")
    }

    private data class LineupJob(
        val id: String, val signature: String, val query: String, val sample: Int,
        val minMs: Long, val maxMs: Long, val maxPerItem: Int, val target: Int,
        val decades: DecadeFilter,
    ) {
        /** Each combination of query + decades gets its own cache file. */
        val cacheKey: String get() = "${id}_${Integer.toHexString(signature.hashCode())}"
    }

    /** Cached lineup if fresh; stale cache is used now and refreshed for next launch; else fetch (with retries). */
    private suspend fun obtain(job: LineupJob, onFailure: () -> Unit): List<Program> {
        val week = Math.floorDiv(System.currentTimeMillis() - Schedule.EPOCH, WEEK_MS)
        val cached = readCache(job.cacheKey)
        if (cached != null && cached.first == job.signature) {
            if (cached.second != week) scope.launch {
                runCatching { writeCache(job.cacheKey, job.signature, week, fetch(job, week)) }
            }
            return cached.third
        }
        var attempt = 0
        while (true) {
            try {
                val programs = fetch(job, week)
                // Empty isn't cached: it may be a transient Archive hiccup rather than a real "nothing".
                if (programs.isNotEmpty()) writeCache(job.cacheKey, job.signature, week, programs)
                return programs
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // language switched / app closing
            } catch (e: Exception) {
                Log.w(TAG, "lineup ${job.id} failed: ${e.message}")
                if (cached != null && cached.third.isNotEmpty()) return cached.third
                onFailure()
                delay(minOf(15_000L * (1 shl attempt.coerceAtMost(3)), 120_000L))
                attempt++
            }
        }
    }

    private suspend fun fetch(job: LineupJob, week: Long): List<Program> = withContext(Dispatchers.IO) {
        val q = "(${job.query}) AND mediatype:movies AND ${config.globalExclude}" +
            (job.decades.queryClause()?.let { " AND $it" } ?: "")
        val hits = requestPermits.withPermit { ArchiveApi.search(q, (job.sample * 4).coerceIn(120, 400)) }
            .filter(::acceptable)
            // The year field can be missing; a year in the title ("Newscast 1987 12 31") still counts.
            .filter { job.decades.isAll || job.decades.accepts(DecadeFilter.yearOf(it.year, it.title)) }
        val shuffled = hits.shuffled(Random(job.id.hashCode().toLong() * 31 + week))
        val out = ArrayList<Program>()
        val seenUrls = HashSet<String>()
        // Work through the shuffled hits in batches until the lineup is full enough.
        for (batch in shuffled.chunked(job.sample).take(3)) {
            val results = batch.map { hit ->
                async {
                    requestPermits.withPermit {
                        runCatching { ArchiveApi.programs(hit, job.minMs, job.maxMs, job.maxPerItem, rejectRegex) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
            for (r in results) for (p in r) if (seenUrls.add(p.url)) out += p
            if (out.size >= job.target) break
        }
        Log.i(TAG, "lineup ${job.id}: ${out.size} programs from ${hits.size} hits")
        out
    }

    private val rejectRegex: Regex? = config.rejectPattern?.let { Regex(it, RegexOption.IGNORE_CASE) }

    /**
     * Keeps items in the lineup's language: the IA "language" field (when present) must match, and
     * nothing hinting at burned-in foreign subtitles ("- Arabic", "subtitles", "ondertiteld"...) gets in.
     */
    private fun acceptable(hit: ArchiveApi.Hit): Boolean {
        val lang = hit.language?.lowercase()?.trim()
        if (!lang.isNullOrEmpty() && config.acceptLanguages.isNotEmpty()) {
            val words = lang.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
            if (words.none { it in config.acceptLanguages }) return false
        }
        val re = rejectRegex ?: return true
        return listOfNotNull(hit.title, hit.description, hit.language).none { re.containsMatchIn(it) }
    }

    private fun readCache(id: String): Triple<String, Long, List<Program>>? = try {
        val f = File(cacheDir, "$id.json")
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("programs")
            Triple(o.getString("signature"), o.getLong("week"), (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Program(
                    itemId = p.getString("item"), title = p.getString("title"),
                    episode = p.optString("episode").ifEmpty { null }, file = p.getString("file"),
                    durationMs = p.getLong("ms"), year = p.optString("year").ifEmpty { null },
                    description = p.optString("desc").ifEmpty { null },
                )
            })
        }
    } catch (e: Exception) {
        null
    }

    /** Drops lineups not used for a month (old decade combinations, old formats). */
    private fun pruneCache() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600_000L
        cacheDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        cacheDir.listFiles { f -> !f.name.contains('_') && f.name.endsWith(".json") }?.forEach { it.delete() } // pre-v3 names
    }

    private fun writeCache(id: String, signature: String, week: Long, programs: List<Program>) {
        val arr = JSONArray()
        for (p in programs) arr.put(JSONObject().apply {
            put("item", p.itemId); put("title", p.title); put("episode", p.episode ?: "")
            put("file", p.file); put("ms", p.durationMs); put("year", p.year ?: ""); put("desc", p.description ?: "")
        })
        val o = JSONObject().put("signature", signature).put("week", week).put("programs", arr)
        val tmp = File(cacheDir, "$id.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(cacheDir, "$id.json"))
    }

    private fun notify(id: String) {
        scope.launch(Dispatchers.Main) { onChanged?.invoke(id) }
    }

    fun shutdown() {
        onChanged = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "ArchiveCable"
        private const val WEEK_MS = 7 * 24 * 3600_000L

        fun parseConfig(text: String): LineupConfig {
            val root = JSONObject(text)
            val f = root.getJSONObject("filler")
            val arr = root.getJSONArray("channels")
            val chans = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                ChannelDef(
                    number = c.getInt("number"),
                    id = c.getString("id"),
                    name = c.getString("name"),
                    title = c.getString("title"),
                    query = c.getString("query"),
                    slotMinutes = c.optInt("slot", 30),
                    minSeconds = (c.optDouble("minMinutes", 5.0) * 60).toInt(),
                    maxSeconds = (c.optDouble("maxMinutes", 180.0) * 60).toInt(),
                    sample = c.optInt("sample", 36),
                    color = runCatching { Color.parseColor(c.optString("color", "#2C3E9F")) }.getOrDefault(Color.BLUE),
                    bumper = BumperStyle.parse(c.optString("bumper")),
                )
            }
            val accept = root.optJSONArray("acceptLanguages")
            return LineupConfig(
                acceptLanguages = accept?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } } ?: emptyList(),
                rejectPattern = root.optString("rejectPattern").ifBlank { null },
                globalExclude = root.optString("globalExclude", "").ifBlank { "-title:(nsfw)" },
                filler = FillerDef(f.getString("query"), f.optInt("sample", 40), (f.optDouble("minMinutes", 1.0) * 60).toInt()),
                channels = chans,
            )
        }
    }
}
