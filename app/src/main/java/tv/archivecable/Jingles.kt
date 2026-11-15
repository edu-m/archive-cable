package tv.archivecable

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Tiny synthesizer for channel idents: every jingle is generated from waveforms, no samples. */
object Jingles {
    private const val RATE = 22050
    private const val VOLUME = 0.30f

    private enum class Wave { SINE, SQUARE, TRIANGLE, SAW, NOISE }

    /** One note: start/length in seconds, MIDI pitch (or Hz when [hz] > 0), optional pitch glide in semitones. */
    private data class Note(
        val at: Double, val len: Double, val midi: Int = 60, val wave: Wave = Wave.SQUARE,
        val vol: Double = 1.0, val glide: Double = 0.0, val hz: Double = 0.0, val decay: Double = 4.0,
    )

    @Volatile private var track: AudioTrack? = null

    fun play(style: BumperStyle, kind: BumperView.Kind) {
        val notes = score(style, kind)
        if (notes.isEmpty()) return
        Thread {
            try {
                val pcm = render(notes)
                stop()
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                t.write(pcm, 0, pcm.size)
                t.play()
                track = t
            } catch (_: Exception) {
                // Audio is a nicety; never let it break playback.
            }
        }.start()
    }

    fun stop() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
    }

    private fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun render(notes: List<Note>): ShortArray {
        val total = notes.maxOf { it.at + it.len } + 0.05
        val buf = FloatArray((total * RATE).toInt())
        var noise = 0x1234567
        for (n in notes) {
            val start = (n.at * RATE).toInt()
            val len = (n.len * RATE).toInt()
            var phase = 0.0
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= buf.size) break
                val tt = i.toDouble() / RATE
                val progress = i.toDouble() / len
                val f = (if (n.hz > 0) n.hz else freq(n.midi)) * 2.0.pow(n.glide * progress / 12.0)
                phase += f / RATE
                val p = phase - phase.toInt()
                val s = when (n.wave) {
                    Wave.SINE -> sin(2 * PI * p)
                    Wave.SQUARE -> if (p < 0.5) 0.6 else -0.6
                    Wave.TRIANGLE -> 4 * abs(p - 0.5) - 1
                    Wave.SAW -> (2 * p - 1) * 0.7
                    Wave.NOISE -> {
                        noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                        (noise and 0xFFFF) / 32768.0 - 1.0
                    }
                }
                val attack = min(1.0, tt / 0.008)
                val release = min(1.0, (n.len - tt) / 0.03)
                val env = attack * release * exp(-tt * n.decay / n.len.coerceAtLeast(0.05) * 0.25)
                buf[idx] += (s * env * n.vol).toFloat()
            }
        }
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(1f)
        return ShortArray(buf.size) { (buf[it] / peak * VOLUME * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun score(style: BumperStyle, kind: BumperView.Kind): List<Note> {
        val brk = kind == BumperView.Kind.BREAK
        return when (style) {
            BumperStyle.NEWS -> if (brk) listOf(
                Note(0.0, 0.18, 72), Note(0.2, 0.18, 67), Note(0.4, 0.6, 72, Wave.SAW, 0.8),
            ) else listOf(
                Note(0.0, 0.15, 60), Note(0.15, 0.15, 67), Note(0.3, 0.15, 72),
                Note(0.5, 1.2, 76, Wave.SAW, 0.7), Note(0.5, 1.2, 72, Wave.SAW, 0.6), Note(0.5, 1.2, 67, Wave.SAW, 0.6),
                Note(0.5, 1.2, 48, Wave.TRIANGLE, 0.9),
            )
            // Classic film-leader "beep" on every countdown number, then the two-pop.
            BumperStyle.LEADER -> (0 until (if (brk) 2 else 3)).map { Note(it * 1.0, 0.07, hz = 1000.0, wave = Wave.SINE, decay = 0.5) } +
                listOf(Note(if (brk) 2.0 else 3.0, 0.08, hz = 1000.0, wave = Wave.SINE, vol = 1.2, decay = 0.5))
            BumperStyle.TERMINAL -> (0 until (if (brk) 6 else 12)).map {
                val pitch = intArrayOf(84, 91, 79, 88, 96, 86)[it % 6]
                Note(it * 0.11, 0.06, pitch, Wave.SQUARE, 0.5, decay = 0.5)
            } + Note(if (brk) 0.8 else 1.5, 0.35, 72, Wave.SQUARE, 0.6)
            BumperStyle.WARP -> listOf(
                Note(0.0, 1.6, 40, Wave.SAW, 0.6, glide = 36.0, decay = 0.3),
                Note(0.0, 1.6, 47, Wave.SINE, 0.5, glide = 36.0, decay = 0.3),
                Note(1.65, 1.6, 64, Wave.TRIANGLE, 0.8), Note(1.65, 1.6, 68, Wave.TRIANGLE, 0.7),
                Note(1.65, 1.6, 71, Wave.TRIANGLE, 0.7), Note(1.65, 1.6, 76, Wave.SINE, 0.6),
            ).let { if (brk) it.take(2) else it }
            BumperStyle.TOON -> listOf(
                Note(0.0, 0.12, 72), Note(0.12, 0.12, 76), Note(0.24, 0.12, 79), Note(0.36, 0.25, 84),
                Note(0.65, 0.45, 70, Wave.SINE, 0.8, glide = 14.0, decay = 0.5),
                Note(1.1, 0.3, 84, Wave.SINE, 0.7, glide = -12.0, decay = 0.5),
                Note(1.45, 0.12, 79), Note(1.6, 0.4, 84, Wave.SQUARE),
            ).let { if (brk) it.drop(4) else it }
            BumperStyle.RETRO -> listOf(
                Note(0.0, 0.9, 72, Wave.SINE, decay = 1.5), Note(0.45, 0.9, 76, Wave.SINE, decay = 1.5),
                Note(0.9, 1.6, 81, Wave.SINE, decay = 1.2), Note(0.9, 1.6, 69, Wave.TRIANGLE, 0.4, decay = 1.2),
            ).let { if (brk) it.drop(1) else it }
            BumperStyle.VHS -> listOf(
                Note(0.0, 0.08, hz = 70.0, wave = Wave.SQUARE, vol = 1.2, decay = 1.0),
                Note(0.1, if (brk) 1.0 else 1.8, wave = Wave.NOISE, vol = 0.15, decay = 0.3),
                Note(0.1, if (brk) 1.0 else 1.8, hz = 60.0, wave = Wave.SINE, vol = 0.35, decay = 0.2),
            )
            BumperStyle.NOIR -> listOf(
                Note(0.0, 0.9, 57, Wave.TRIANGLE, decay = 1.0), Note(0.8, 0.6, 53, Wave.TRIANGLE, decay = 1.0),
                Note(1.3, 1.8, 52, Wave.TRIANGLE, decay = 0.8), Note(1.3, 1.8, 40, Wave.SINE, 0.7, decay = 0.6),
            ).let { if (brk) it.drop(2) else it }
            BumperStyle.CURTAIN -> listOf(
                Note(0.0, 0.2, 67, Wave.SAW), Note(0.22, 0.2, 72, Wave.SAW), Note(0.44, 0.2, 76, Wave.SAW),
                Note(0.7, 1.6, 79, Wave.SAW, 0.8), Note(0.7, 1.6, 76, Wave.SAW, 0.6), Note(0.7, 1.6, 72, Wave.SAW, 0.6),
                Note(0.7, 1.6, 48, Wave.TRIANGLE),
                Note(0.0, 2.3, wave = Wave.NOISE, vol = 0.05, decay = 0.2),
            ).let { if (brk) listOf(Note(0.0, 0.2, 72, Wave.SAW), Note(0.25, 0.8, 67, Wave.SAW)) else it }
            BumperStyle.NEON -> (0 until (if (brk) 8 else 16)).flatMap {
                val arp = intArrayOf(69, 72, 76, 81, 67, 71, 74, 79)
                listOf(Note(it * 0.125, 0.11, arp[it % 8], Wave.SAW, 0.6, decay = 1.5)) +
                    if (it % 4 == 0) listOf(Note(it * 0.125, 0.45, arp[it % 8] - 24, Wave.SQUARE, 0.6)) else emptyList()
            }
        }
    }
}
