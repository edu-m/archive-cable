package tv.archivecable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private fun def(slot: Int) = ChannelDef(4, "t$slot", "TEST", "Test", "q", slot, 60, 7200, 10, 0, BumperStyle.RETRO)
    private fun prog(i: Int, min: Double) =
        Program("item$i", "Show $i", null, "f$i.mp4", (min * 60_000).toLong(), null, null)

    private val programs = listOf(prog(1, 28.5), prog(2, 22.0), prog(3, 31.5), prog(4, 47.0), prog(5, 95.0), prog(6, 12.0))
    private val filler = listOf(prog(100, 3.0), prog(101, 10.0), prog(102, 0.5))

    @Test fun airingsAreContiguousAndAligned() {
        val s = Schedule(def(30), programs)
        val t0 = System.currentTimeMillis()
        val list = s.airings(t0, t0 + 48 * 3600_000L)
        assertTrue(list.size > 20)
        for (i in 1 until list.size) assertEquals(list[i - 1].end, list[i].start)
        for (a in list) {
            assertEquals(0, (a.start - Schedule.EPOCH) % (30 * 60_000L))
            assertTrue(a.programEnd <= a.end)
        }
    }

    @Test fun overrunWithinToleranceIsTrimmedNotDoubled() {
        val s = Schedule(def(30), listOf(prog(3, 31.5)))
        val a = s.airingAt(System.currentTimeMillis())!!
        assertEquals(30 * 60_000L, a.end - a.start)
        val p = s.playableAt(a.start + 1000, emptyList(), emptySet()) as Playable.Media
        assertEquals(30 * 60_000L, p.clipEndMs)
    }

    @Test fun deterministic() {
        val t = 1_800_000_000_000L
        assertEquals(Schedule(def(30), programs).airingAt(t), Schedule(def(30), programs.reversed()).airingAt(t))
    }

    @Test fun gapsAreFilledWithCommercialsThenStandBy() {
        val s = Schedule(def(30), listOf(prog(2, 22.0)))
        val a = s.airingAt(System.currentTimeMillis())!!
        var t = a.programEnd
        var covered = 0L
        while (t < a.end) {
            val p = s.playableAt(t, filler, emptySet())
            p as Playable.Media
            assertTrue(p.isFiller)
            val len = p.clipEndMs!! - p.clipStartMs
            assertTrue(p.clipEndMs!! <= p.program.durationMs)
            t += len - p.positionMs
            covered += len - p.positionMs
        }
        assertEquals(a.end - a.programEnd, covered)
        assertTrue(s.playableAt(a.programEnd, emptyList(), emptySet()) is Playable.StandBy)
    }

    @Test fun brokenProgramFallsBackToFiller() {
        val s = Schedule(def(30), listOf(prog(2, 22.0)))
        val a = s.airingAt(System.currentTimeMillis())!!
        val p = s.playableAt(a.start + 5000, filler, setOf(a.program.url))
        assertTrue(p is Playable.Media && p.isFiller)
    }

    /** Regression: every instant across show boundaries must have something to play (no "stand by" flashes). */
    @Test fun noStandByFlashesAtBoundaries() {
        val odd = listOf(prog(1, 6.37), prog(2, 7.1234), prog(3, 22.5017), prog(4, 28.999), prog(5, 3.3333))
        for (slot in listOf(0, 30)) {
            val s = Schedule(def(slot), odd)
            val t0 = System.currentTimeMillis()
            for (a in s.airings(t0, t0 + 6 * 3600_000L)) {
                var t = a.programEnd - 3000
                while (t < a.end + 3000) {
                    val p = s.playableAt(t, filler, emptySet())
                    assertTrue("slot=$slot t=${t - a.programEnd}ms after program end: $p", p is Playable.Media)
                    t += 97
                }
            }
        }
    }

    @Test fun parsesLengths() {
        assertEquals(1653220L, ArchiveApi.parseLength("1653.22"))
        assertEquals(1653000L, ArchiveApi.parseLength("27:33"))
        assertEquals(3723000L, ArchiveApi.parseLength("1:02:03"))
        assertEquals(0L, ArchiveApi.parseLength("n/a"))
    }
}
