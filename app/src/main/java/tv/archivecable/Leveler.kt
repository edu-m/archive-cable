package tv.archivecable

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Automatic volume leveling ("auto volume") for interleaved 16-bit PCM.
 *
 * Archive recordings range from nearly inaudible VHS transfers to clipped, ear-piercing ones. This
 * nudges everything toward one listening level:
 *  - loudness is measured as the RMS of a 100 Hz high-passed mono mix (so mains hum and rumble don't
 *    count as loud), with a fast-rising, slow-falling estimate so pauses don't read as "quiet";
 *  - gain moves toward TARGET / loudness, limited to ±20 dB: down quickly (0.3 s), up slowly
 *    (2.5 s); near-silence is never boosted;
 *  - right after a new clip starts or a seek ([restart]) it adapts quickly for 1.5 s;
 *  - a peak limiter after the gain guarantees the output never clips.
 */
class Leveler(private val sampleRate: Int, private val channels: Int) {

    @Volatile var enabled: Boolean = true

    /** Gain currently applied to the signal (smoothed per sample). */
    var currentGain = 1f
        private set
    private var targetGain = 1f
    private var levelPow = -1f
    private var blockSum = 0.0
    private var blockCount = 0
    private val blockLen = max(1, sampleRate / 20) // 50 ms analysis blocks
    private val blockSeconds = blockLen.toDouble() / sampleRate
    private var fastFrames = 0

    private val hpA: Float
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    private var limEnv = 0f
    private val limRelease = exp(-1.0 / (LIMIT_RELEASE_S * sampleRate)).toFloat()
    private val gainSmooth = (1 - exp(-1.0 / (GAIN_SMOOTH_S * sampleRate))).toFloat()
    private val frame = FloatArray(channels.coerceAtLeast(1))

    init {
        val rc = 1.0 / (2 * Math.PI * HIGHPASS_HZ)
        val dt = 1.0 / sampleRate
        hpA = (rc / (rc + dt)).toFloat()
        restart()
    }

    /** A new clip started or the viewer seeked: re-measure quickly, keeping the current gain as a start. */
    fun restart() {
        fastFrames = (FAST_ADAPT_S * sampleRate).toInt()
        levelPow = -1f
        blockSum = 0.0
        blockCount = 0
    }

    /** Processes [frames] interleaved frames of [buf] in place. */
    fun process(buf: ShortArray, frames: Int) {
        val ch = channels
        for (f in 0 until frames) {
            val base = f * ch
            var mono = 0f
            for (c in 0 until ch) mono += buf[base + c]
            mono /= ch * 32768f
            val hp = hpA * (hpPrevOut + mono - hpPrevIn)
            hpPrevIn = mono
            hpPrevOut = hp
            blockSum += (hp * hp).toDouble()
            if (++blockCount >= blockLen) {
                onBlock((blockSum / blockCount).toFloat())
                blockSum = 0.0
                blockCount = 0
            }
            if (fastFrames > 0) fastFrames--

            val wanted = if (enabled) targetGain else 1f
            currentGain += (wanted - currentGain) * gainSmooth
            var peak = 0f
            for (c in 0 until ch) {
                val s = buf[base + c] / 32768f * currentGain
                frame[c] = s
                peak = max(peak, abs(s))
            }
            limEnv = if (peak > limEnv) peak else limEnv * limRelease + peak * (1 - limRelease)
            val lim = if (limEnv > LIMIT) LIMIT / limEnv else 1f
            for (c in 0 until ch) {
                val out = (frame[c] * lim).coerceIn(-1f, 1f)
                buf[base + c] = (out * 32767f).toInt().toShort()
            }
        }
    }

    private fun onBlock(pow: Float) {
        val fast = fastFrames > 0
        val rising = levelPow < 0 || pow > levelPow
        val tauLevel = when {
            fast -> if (rising) 0.15 else 0.5
            rising -> 0.4
            else -> 3.0 // slow fall: speech pauses don't make the leveler crank up the hiss
        }
        levelPow = if (levelPow < 0) pow else levelPow + (pow - levelPow) * coef(tauLevel)
        val rms = sqrt(levelPow)
        if (rms < GATE) return // silence: hold the gain
        val desired = (TARGET / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        val tau = when {
            fast -> 0.25
            desired < targetGain -> ATTACK_S
            else -> RELEASE_S
        }
        targetGain += (desired - targetGain) * coef(tau)
    }

    private fun coef(tau: Double) = (1 - exp(-blockSeconds / tau)).toFloat()

    companion object {
        /** Target RMS (-20 dBFS), about where TV dialogue sits. */
        const val TARGET = 0.1f
        const val MIN_GAIN = 0.1f   // -20 dB
        const val MAX_GAIN = 10f    // +20 dB
        const val GATE = 0.003f     // -50 dBFS: below this, don't chase the level
        const val LIMIT = 0.9f      // -1 dBFS ceiling
        private const val ATTACK_S = 0.3
        private const val RELEASE_S = 2.5
        private const val FAST_ADAPT_S = 1.5
        private const val GAIN_SMOOTH_S = 0.02
        private const val LIMIT_RELEASE_S = 0.08
        private const val HIGHPASS_HZ = 100.0
    }
}
