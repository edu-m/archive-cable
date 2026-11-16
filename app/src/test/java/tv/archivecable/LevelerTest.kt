package tv.archivecable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class LevelerTest {
    private val rate = 48_000

    /** Stereo sine at [amp] (0..1 of full scale), [seconds] long. */
    private fun sine(amp: Double, seconds: Double, hz: Double = 440.0): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i -> (sin(2 * PI * hz * (i / 2) / rate) * amp * 32767).toInt().toShort() }
    }

    private fun run(lv: Leveler, input: ShortArray): ShortArray {
        val out = input.copyOf()
        var pos = 0
        while (pos < out.size) { // feed in decoder-sized chunks
            val n = minOf(4096, out.size - pos)
            val chunk = out.copyOfRange(pos, pos + n)
            lv.process(chunk, n / 2)
            chunk.copyInto(out, pos)
            pos += n
        }
        return out
    }

    private fun rmsDb(buf: ShortArray, fromSeconds: Double): Double {
        val start = (fromSeconds * rate).toInt() * 2
        var sum = 0.0
        for (i in start until buf.size) { val s = buf[i] / 32768.0; sum += s * s }
        return 20 * log10(sqrt(sum / (buf.size - start)))
    }

    private fun peak(buf: ShortArray) = buf.maxOf { abs(it.toInt()) } / 32768.0

    private val targetDb = 20 * log10(Leveler.TARGET.toDouble())

    @Test fun quietRecordingIsBoostedTowardTarget() {
        val out = run(Leveler(rate, 2), sine(0.02, 12.0)) // about -37 dBFS RMS
        assertEquals(targetDb, rmsDb(out, 8.0), 3.0)
    }

    @Test fun loudRecordingIsTurnedDownAndNeverClips() {
        val out = run(Leveler(rate, 2), sine(1.0, 6.0)) // full-scale, -3 dBFS RMS
        assertEquals(targetDb, rmsDb(out, 3.0), 3.0)
        assertTrue("peak ${peak(out)}", peak(out) <= Leveler.LIMIT + 0.01)
    }

    @Test fun firstMomentsOfALoudClipAreLimited() {
        val out = run(Leveler(rate, 2), sine(1.0, 1.0))
        assertTrue(peak(out) <= Leveler.LIMIT + 0.01)
    }

    @Test fun silenceAndHissAreNotPumpedUp() {
        val rnd = Random(1)
        val hiss = ShortArray(rate * 2 * 8) { (rnd.nextDouble(-1.0, 1.0) * 0.001 * 32767).toInt().toShort() } // -63 dBFS
        val lv = Leveler(rate, 2)
        run(lv, hiss)
        assertEquals(1f, lv.currentGain, 0.05f)
    }

    @Test fun adaptsQuicklyWhenALoudCommercialIsFollowedByAQuietShow() {
        val lv = Leveler(rate, 2)
        run(lv, sine(0.9, 5.0))
        lv.restart() // new clip
        val out = run(lv, sine(0.03, 4.0))
        assertEquals(targetDb, rmsDb(out, 2.5), 4.0)
    }

    @Test fun offMeansUntouched() {
        val lv = Leveler(rate, 2).apply { enabled = false }
        val input = sine(0.02, 2.0)
        val out = run(lv, input)
        assertEquals(rmsDb(input, 0.5), rmsDb(out, 0.5), 0.2)
    }
}
