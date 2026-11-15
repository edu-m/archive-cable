package tv.archivecable

/** A themed channel, defined in assets/channels.json. */
data class ChannelDef(
    val number: Int,
    val id: String,
    val name: String,
    val title: String,
    val query: String,
    /** Minutes programs are aligned to (0 = back-to-back). */
    val slotMinutes: Int,
    val minSeconds: Int,
    val maxSeconds: Int,
    val sample: Int,
    val color: Int,
    val bumper: BumperStyle,
)

data class FillerDef(val query: String, val sample: Int, val minSeconds: Int)

data class LineupConfig(
    /** Accepted values of the IA "language" field (substring match); items without one are kept. */
    val acceptLanguages: List<String>,
    /** Regex (case-insensitive) on titles, descriptions and file names; matches are dropped. */
    val rejectPattern: String?,
    val globalExclude: String,
    val filler: FillerDef,
    val channels: List<ChannelDef>,
) {
    val filterSignature: String get() = "${acceptLanguages.joinToString(",")}|$rejectPattern"
}

/** One playable video file from an Internet Archive item. */
data class Program(
    val itemId: String,
    val title: String,
    val episode: String?,
    val file: String,
    val durationMs: Long,
    val year: String?,
    val description: String?,
) {
    val url: String get() = ArchiveApi.downloadUrl(itemId, file)
    val displayTitle: String get() = title
}

/** A program occupying a time slot on a channel. [end] is the slot end, [programEnd] when the video itself ends. */
data class Airing(
    val program: Program,
    val start: Long,
    val end: Long,
    val programEnd: Long,
) {
    fun progress(now: Long): Float =
        ((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** What a channel is showing at a given instant. */
sealed class Playable {
    /**
     * Play [program]'s file, clipped to [clipStartMs, clipEndMs] (null = to the end),
     * starting [positionMs] into the clip.
     */
    data class Media(
        val program: Program,
        val airing: Airing,
        val clipStartMs: Long,
        val clipEndMs: Long?,
        val positionMs: Long,
        val isFiller: Boolean,
    ) : Playable()

    /** Nothing to play: show the "please stand by" card until [untilMs]. */
    data class StandBy(val airing: Airing?, val untilMs: Long) : Playable()
}
