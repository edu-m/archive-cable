package tv.archivecable

/**
 * Which decades are on the air. The 1940s box also covers anything older and the 2010s box anything
 * newer; [undated] decides about items the Internet Archive has no year for.
 */
data class DecadeFilter(val decades: Set<Int>, val undated: Boolean) {

    val isAll: Boolean get() = decades.containsAll(ALL) && undated

    /** Stable key for caches: each combination keeps its own lineups, so switching back is instant. */
    val signature: String get() = if (isAll) "all" else decades.sorted().joinToString(",") + if (undated) "+u" else ""

    /** Clause appended to the IA search, or null when everything is selected. */
    fun queryClause(): String? {
        if (isAll) return null
        val parts = ArrayList<String>()
        var start: Int? = null
        var prev = 0
        for (d in ALL) {
            if (d in decades) {
                if (start == null) start = d
                prev = d
            } else if (start != null) {
                parts += range(start, prev); start = null
            }
        }
        if (start != null) parts += range(start, prev)
        if (undated) parts += "(*:* -year:[* TO *])"
        return if (parts.isEmpty()) null else parts.joinToString(" OR ", "(", ")")
    }

    private fun range(from: Int, to: Int): String {
        val lo = if (from == ALL.first()) 1800 else from
        val hi = if (to == ALL.last()) 2100 else to + 9
        return "year:[$lo TO $hi]"
    }

    fun accepts(year: Int?): Boolean = if (year == null) undated else decadeOf(year) in decades

    companion object {
        val ALL: List<Int> = (1940..2010 step 10).toList()
        val EVERYTHING = DecadeFilter(ALL.toSet(), undated = true)

        fun decadeOf(year: Int): Int = (year / 10 * 10).coerceIn(ALL.first(), ALL.last())

        private val YEAR_IN_TEXT = Regex("\\b(19[0-9]{2}|20[0-2][0-9])\\b")

        /**
         * Production year: the IA "year" field or a year written in the title ("Newscast 1987 12 31"),
         * whichever is earlier. Re-uploads often carry the upload year ("1950. D.O.A." tagged 2023).
         */
        fun yearOf(field: String?, title: String): Int? {
            val fromField = field?.let { YEAR_IN_TEXT.find(it)?.value?.toInt() }
            val fromTitle = YEAR_IN_TEXT.find(title)?.value?.toInt()
            return listOfNotNull(fromField, fromTitle).minOrNull()
        }
    }
}
