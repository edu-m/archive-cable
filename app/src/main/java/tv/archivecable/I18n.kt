package tv.archivecable

import java.util.Locale

/**
 * UI language. Switching it also swaps the whole channel lineup (assets/channels_<code>.json),
 * so the programming itself changes language, not just the menus.
 */
object I18n {
    data class Language(val code: String, val label: String, val locale: Locale, val clock24h: Boolean)

    val LANGUAGES = listOf(
        Language("en", "ENGLISH", Locale.US, clock24h = false),
        Language("it", "ITALIANO", Locale.ITALY, clock24h = true),
    )

    @Volatile var current: Language = LANGUAGES[0]
        private set

    val code get() = current.code

    fun set(code: String?) {
        current = LANGUAGES.firstOrNull { it.code == code } ?: LANGUAGES[0]
        Retro.onLanguageChanged()
    }

    private val strings: Map<String, Map<String, String>> = mapOf(
        "acquiring_listings" to mapOf("en" to "ACQUIRING LISTINGS...", "it" to "RICERCA PALINSESTO..."),
        "no_listings_retrying" to mapOf("en" to "NO LISTINGS - RETRYING", "it" to "NESSUN PALINSESTO - NUOVO TENTATIVO"),
        "no_listings" to mapOf("en" to "NO LISTINGS AVAILABLE", "it" to "PALINSESTO NON DISPONIBILE"),
        "commercial_break" to mapOf("en" to "[ COMMERCIAL BREAK ]", "it" to "[ PAUSA PUBBLICITARIA ]"),
        "next" to mapOf("en" to "NEXT", "it" to "A SEGUIRE"),
        "min_left" to mapOf("en" to "%d min left", "it" to "ancora %d min"),
        "channel" to mapOf("en" to "CHANNEL", "it" to "CANALE"),
        "ch" to mapOf("en" to "CH", "it" to "CH"),
        "play" to mapOf("en" to "PLAY", "it" to "PLAY"),
        "paused" to mapOf("en" to "PAUSED", "it" to "PAUSA"),
        "acquiring_signal" to mapOf("en" to "ACQUIRING SIGNAL", "it" to "RICERCA SEGNALE"),
        "no_signal" to mapOf("en" to "NO SIGNAL", "it" to "NESSUN SEGNALE"),
        "signal_lost" to mapOf("en" to "SIGNAL LOST", "it" to "SEGNALE PERSO"),
        "technical_difficulties" to mapOf("en" to "TECHNICAL DIFFICULTIES - PLEASE STAND BY", "it" to "PROBLEMI TECNICI - LE TRASMISSIONI RIPRENDERANNO A BREVE"),
        "stand_by" to mapOf("en" to "PLEASE STAND BY", "it" to "INTERVALLO"),
        "resumes_at" to mapOf("en" to "PROGRAMMING RESUMES AT %s", "it" to "LE TRASMISSIONI RIPRENDONO ALLE %s"),
        "starts_in_min" to mapOf("en" to "STARTS IN %d MIN", "it" to "INIZIA TRA %d MIN"),
        "starts_in_h" to mapOf("en" to "STARTS IN %dh %dm", "it" to "INIZIA TRA %dh %dm"),
        "guide_hints" to mapOf(
            "en" to "UP/DOWN: CHANNEL   LEFT/RIGHT: TIME   OK: WATCH   BACK: EXIT",
            "it" to "SU/GIU: CANALE   SINISTRA/DESTRA: ORARIO   OK: GUARDA   INDIETRO: ESCI",
        ),
        "press_back_again" to mapOf("en" to "Press BACK again to turn off Archive Cable", "it" to "Premi di nuovo INDIETRO per spegnere Archive Cable"),
        "setup" to mapOf("en" to "SETUP", "it" to "IMPOSTAZIONI"),
        "setup_hint" to mapOf("en" to "UP/DOWN: ITEM   LEFT/RIGHT: CHANGE   OK: SAVE   BACK: CANCEL", "it" to "SU/GIU: VOCE   SINISTRA/DESTRA: CAMBIA   OK: SALVA   INDIETRO: ANNULLA"),
        "row_language" to mapOf("en" to "LANGUAGE / LINGUA", "it" to "LINGUA / LANGUAGE"),
        "row_picture" to mapOf("en" to "PICTURE", "it" to "IMMAGINE"),
        "row_transitions" to mapOf("en" to "TRANSITIONS", "it" to "SIGLE"),
        "language_note" to mapOf("en" to "CHANGES THE WHOLE CHANNEL LINEUP", "it" to "CAMBIA TUTTA LA PROGRAMMAZIONE"),
        "aspect_auto" to mapOf("en" to "AUTO", "it" to "AUTOMATICO"),
        "aspect_stretch" to mapOf("en" to "STRETCH", "it" to "ALLARGA"),
        "aspect_zoom" to mapOf("en" to "ZOOM", "it" to "ZOOM"),
        "aspect_original" to mapOf("en" to "ORIGINAL", "it" to "ORIGINALE"),
        "aspect_auto_hint" to mapOf("en" to "STRETCHES 4:3 SHOWS TO FILL THE SCREEN", "it" to "ALLARGA I PROGRAMMI 4:3 A TUTTO SCHERMO"),
        "aspect_stretch_hint" to mapOf("en" to "STRETCHES EVERYTHING TO FILL THE SCREEN", "it" to "ALLARGA TUTTO A TUTTO SCHERMO"),
        "aspect_zoom_hint" to mapOf("en" to "FILLS THE SCREEN, CROPS TOP AND BOTTOM", "it" to "RIEMPIE LO SCHERMO TAGLIANDO SOPRA E SOTTO"),
        "aspect_original_hint" to mapOf("en" to "ORIGINAL SHAPE WITH BLACK BARS", "it" to "FORMATO ORIGINALE CON BANDE NERE"),
        "on" to mapOf("en" to "ON", "it" to "SI"),
        "off" to mapOf("en" to "OFF", "it" to "NO"),
        "transitions_hint" to mapOf("en" to "CHANNEL IDENTS AND JINGLES BETWEEN SHOWS", "it" to "SIGLE E JINGLE DEL CANALE TRA I PROGRAMMI"),
        "row_overscan" to mapOf("en" to "OVERSCAN (SIZE)", "it" to "OVERSCAN (DIMENSIONE)"),
        "row_offset_x" to mapOf("en" to "POSITION X", "it" to "POSIZIONE X"),
        "row_offset_y" to mapOf("en" to "POSITION Y", "it" to "POSIZIONE Y"),
        "overscan_hint" to mapOf("en" to "SHRINK UNTIL ALL 4 RED CORNERS ARE VISIBLE   0: RESET", "it" to "RIDUCI FINCHE I 4 ANGOLI ROSSI SONO VISIBILI   0: RIPRISTINA"),
        "offset_hint" to mapOf("en" to "CENTER THE PICTURE ON THE TUBE   0: RESET", "it" to "CENTRA L'IMMAGINE SUL TUBO   0: RIPRISTINA"),
        "decade_first" to mapOf("en" to "%1\$ds & EARLIER", "it" to "ANNI '%2\$02d E PRIMA"),
        "decade" to mapOf("en" to "%1\$ds", "it" to "ANNI '%2\$02d"),
        "decade_2000s" to mapOf("en" to "%1\$ds", "it" to "ANNI %1\$d"),
        "decade_last" to mapOf("en" to "%1\$ds & LATER", "it" to "ANNI %1\$d E DOPO"),
        "undated" to mapOf("en" to "UNDATED ITEMS", "it" to "SENZA DATA"),
        "decades_hint" to mapOf("en" to "TICKED DECADES ARE ON THE AIR   OK RELOADS THE LINEUP", "it" to "I DECENNI SPUNTATI VANNO IN ONDA   OK RICARICA"),
        "nothing_in_decades" to mapOf("en" to "NOTHING FROM SELECTED DECADES", "it" to "NIENTE DAI DECENNI SCELTI"),
        "about" to mapOf(
            "en" to "NOT AFFILIATED WITH THE INTERNET ARCHIVE - SUPPORT IT: ARCHIVE.ORG/DONATE",
            "it" to "NON AFFILIATO A INTERNET ARCHIVE - SOSTIENILO: ARCHIVE.ORG/DONATE",
        ),
        "now_showing" to mapOf("en" to "NOW SHOWING", "it" to "ORA IN ONDA"),
        "right_back" to mapOf("en" to "WE'LL BE RIGHT BACK", "it" to "TORNIAMO SUBITO"),
        "after_break" to mapOf("en" to "AFTER THE BREAK: %s", "it" to "DOPO LA PUBBLICITA: %s"),
        "feature_presentation" to mapOf("en" to "FEATURE PRESENTATION", "it" to "PRIMA VISIONE"),
        "intermission" to mapOf("en" to "INTERMISSION", "it" to "INTERVALLO"),
    )

    fun t(key: String): String = strings[key]?.let { it[code] ?: it["en"] } ?: key

    fun t(key: String, vararg args: Any): String = String.format(current.locale, t(key), *args)
}
