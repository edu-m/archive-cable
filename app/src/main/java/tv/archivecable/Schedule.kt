package tv.archivecable

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * A deterministic, endlessly looping broadcast schedule for one channel.
 *
 * Time is divided into cycles anchored at [EPOCH]. Every cycle airs every program once, in an order
 * shuffled per cycle, so the schedule is the same no matter when the app is opened: tune in and the
 * channel is already mid-show, just like real cable.
 */
class Schedule(private val def: ChannelDef, programs: List<Program>) {

    private val programs = programs.sortedBy { it.url }
    private val slotMs = def.slotMinutes * 60_000L
    private val lengths: LongArray = LongArray(this.programs.size) { slotLength(this.programs[it].durationMs) }
    private val cycleMs: Long = lengths.sum().coerceAtLeast(1)
    private val seed: Long = def.id.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong()
    private val orderCache = object : LinkedHashMap<Long, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, IntArray>?) = size > 6
    }

    val isEmpty get() = programs.isEmpty()
    val size get() = programs.size

    private fun slotLength(durationMs: Long): Long {
        // Back-to-back channels: the slot is exactly the program (rounding up left sub-second dead air).
        if (slotMs <= 0) return durationMs.coerceAtLeast(1000)
        // Shows running a little long (credits) get trimmed instead of taking a whole extra slot.
        val effective = max(durationMs - OVERRUN_TOLERANCE_MS, 1)
        val slots = (effective + slotMs - 1) / slotMs
        return max(slots, 1) * slotMs
    }

    @Synchronized
    private fun orderFor(cycle: Long): IntArray = orderCache.getOrPut(cycle) {
        val idx = IntArray(programs.size) { it }
        val rnd = Random(seed xor (cycle * 1_000_003L))
        for (i in idx.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = idx[i]; idx[i] = idx[j]; idx[j] = t
        }
        idx
    }

    fun airingAt(t: Long): Airing? {
        if (programs.isEmpty()) return null
        val rel = t - EPOCH
        val cycle = Math.floorDiv(rel, cycleMs)
        var r = Math.floorMod(rel, cycleMs)
        var start = EPOCH + cycle * cycleMs
        for (i in orderFor(cycle)) {
            val len = lengths[i]
            if (r < len) {
                val p = programs[i]
                return Airing(p, start, start + len, start + min(p.durationMs, len))
            }
            r -= len
            start += len
        }
        return null // unreachable
    }

    /** Airings overlapping [from, to). */
    fun airings(from: Long, to: Long): List<Airing> {
        val out = ArrayList<Airing>()
        var a = airingAt(from) ?: return out
        while (a.start < to && out.size < 200) {
            out += a
            a = airingAt(a.end) ?: break
        }
        return out
    }

    /**
     * What should be on screen at [t]. Gaps between a program's end and its slot end are filled
     * with vintage commercials from [filler]; URLs in [avoid] (known broken) are skipped.
     */
    fun playableAt(t: Long, filler: List<Program>, avoid: Set<String>): Playable {
        val a = airingAt(t) ?: return Playable.StandBy(null, t + 30_000)
        val brokenProgram = a.program.url in avoid
        if (t < a.programEnd && !brokenProgram) {
            val clipEnd = if (a.program.durationMs > a.end - a.start) a.end - a.start else null
            return Playable.Media(a.program, a, 0, clipEnd, t - a.start, isFiller = false)
        }
        // A sliver of dead air (shorter than any commercial) just rolls into the next show.
        if (a.end - t < MIN_GAP_MS) return startOfNext(a, filler, avoid)
        return when (val f = fillerAt(a, t, if (brokenProgram) a.start else a.programEnd, filler, avoid)) {
            is Playable.StandBy -> if (f.untilMs - t < MIN_GAP_MS) startOfNext(a, filler, avoid) else f
            else -> f
        }
    }

    /** The show after [a], from its first frame (the viewer ends up < [MIN_GAP_MS] behind live). */
    private fun startOfNext(a: Airing, filler: List<Program>, avoid: Set<String>): Playable =
        playableAt(a.end, filler, avoid)

    private fun fillerAt(a: Airing, t: Long, gapStart: Long, pool: List<Program>, avoid: Set<String>): Playable {
        if (pool.isEmpty()) return Playable.StandBy(a, a.end)
        val h = mix(a.start xor seed)
        var pos = gapStart
        var j = 0
        while (pos < a.end && j < 1000) {
            val p = pool[Math.floorMod(h + j, pool.size.toLong()).toInt()]
            var len = minOf(p.durationMs, a.end - pos, MAX_FILLER_CHUNK_MS)
            // Never leave a tail too short to fill: stretch this chunk to the end of the gap if it can.
            val rest = a.end - pos - len
            if (rest in 1 until MIN_GAP_MS && p.durationMs >= len + rest) len += rest
            if (len < 1000) break
            if (t < pos + len) {
                if (p.url in avoid) return Playable.StandBy(a, pos + len)
                val maxOffset = p.durationMs - len
                val offset = if (maxOffset > 1000) (Math.floorMod(mix(h + j * 31), maxOffset) / 1000) * 1000 else 0
                return Playable.Media(p, a, offset, offset + len, t - pos, isFiller = true)
            }
            pos += len
            j++
        }
        return Playable.StandBy(a, a.end)
    }

    companion object {
        /** 2024-01-01T00:00:00Z: aligned to the hour in every whole-hour time zone. */
        const val EPOCH = 1_704_067_200_000L
        private const val OVERRUN_TOLERANCE_MS = 150_000L
        private const val MAX_FILLER_CHUNK_MS = 180_000L
        private const val MIN_GAP_MS = 2_000L

        fun mix(x: Long): Long {
            var z = x + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return (z xor (z ushr 31)) and Long.MAX_VALUE
        }
    }
}
