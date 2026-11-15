package tv.archivecable

import android.content.Context

/** Picture size, like the "ASPECT" button on an old set-top box. */
enum class AspectMode(val labelKey: String, val hintKey: String) {
    /** Stretch 4:3-shaped video to fill a 16:9 screen; leave widescreen video alone. */
    AUTO("aspect_auto", "aspect_auto_hint"),
    STRETCH("aspect_stretch", "aspect_stretch_hint"),
    ZOOM("aspect_zoom", "aspect_zoom_hint"),
    ORIGINAL("aspect_original", "aspect_original_hint"),
}

/** Viewer preferences persisted across launches (language lives in [I18n], persisted here too). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var language: String?
        get() = prefs.getString("language", null)
        set(v) = prefs.edit().putString("language", v).apply()

    var aspect: AspectMode
        get() = runCatching { AspectMode.valueOf(prefs.getString("aspect", null)!!) }.getOrDefault(AspectMode.AUTO)
        set(v) = prefs.edit().putString("aspect", v.name).apply()

    var transitions: Boolean
        get() = prefs.getBoolean("transitions", true)
        set(v) = prefs.edit().putBoolean("transitions", v).apply()

    /** Whole-picture size in percent, to fit a CRT's visible area (overscan compensation). */
    var overscan: Int
        get() = prefs.getInt("overscan", 100).coerceIn(OVERSCAN_MIN, OVERSCAN_MAX)
        set(v) = prefs.edit().putInt("overscan", v).apply()

    /** Whole-picture shift, in tenths of a percent of screen width / height. */
    var offsetX: Int
        get() = prefs.getInt("offset_x", 0).coerceIn(-OFFSET_MAX, OFFSET_MAX)
        set(v) = prefs.edit().putInt("offset_x", v).apply()

    var offsetY: Int
        get() = prefs.getInt("offset_y", 0).coerceIn(-OFFSET_MAX, OFFSET_MAX)
        set(v) = prefs.edit().putInt("offset_y", v).apply()

    var decadeFilter: DecadeFilter
        get() {
            val set = prefs.getStringSet("decades", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: DecadeFilter.ALL.toSet()
            return DecadeFilter(set, prefs.getBoolean("undated", true))
        }
        set(v) = prefs.edit().putStringSet("decades", v.decades.map { it.toString() }.toSet()).putBoolean("undated", v.undated).apply()

    fun lastChannel(lang: String): String? = prefs.getString("channel_$lang", null)
    fun setLastChannel(lang: String, id: String) = prefs.edit().putString("channel_$lang", id).apply()

    companion object {
        const val OVERSCAN_MIN = 75
        const val OVERSCAN_MAX = 110
        const val OFFSET_MAX = 100 // ±10%
        const val OFFSET_STEP = 5  // 0.5%
    }
}
