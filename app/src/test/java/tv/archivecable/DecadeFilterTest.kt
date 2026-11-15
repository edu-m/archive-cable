package tv.archivecable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecadeFilterTest {
    @Test fun everythingAddsNoClause() = assertNull(DecadeFilter.EVERYTHING.queryClause())

    @Test fun mergesContiguousDecadesAndOpensTheEnds() {
        val f = DecadeFilter(setOf(1940, 1950, 1980, 1990, 2010), undated = false)
        assertEquals("(year:[1800 TO 1959] OR year:[1980 TO 1999] OR year:[2010 TO 2100])", f.queryClause())
    }

    @Test fun undatedClause() {
        assertEquals("(year:[1970 TO 1979] OR (*:* -year:[* TO *]))", DecadeFilter(setOf(1970), true).queryClause())
    }

    @Test fun acceptsByDecadeWithOpenEnds() {
        val f = DecadeFilter(setOf(1940, 2010), undated = false)
        assertTrue(f.accepts(1933))
        assertTrue(f.accepts(2021))
        assertFalse(f.accepts(1987))
        assertFalse(f.accepts(null))
    }

    @Test fun yearFromFieldOrTitle() {
        assertEquals(1987, DecadeFilter.yearOf(null, "Newscast 1987 12 31"))
        assertEquals(1975, DecadeFilter.yearOf("1975", "ABC Evening News"))
        assertNull(DecadeFilter.yearOf(null, "Get Smart"))
        assertEquals(1950, DecadeFilter.yearOf("2023", "1950. D.O.A."))
    }
}
