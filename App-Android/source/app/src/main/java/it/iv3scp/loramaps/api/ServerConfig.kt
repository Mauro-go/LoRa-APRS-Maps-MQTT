package it.iv3scp.loramaps.api

import android.content.Context

object ServerConfig {

    private const val PREFS_NAME =
        "lora_maps_settings"

    private const val KEY_BASE_URL =
        "base_url"

    /*
     * Durante lo sviluppo manteniamo il nostro server
     * come valore iniziale.
     *
     * Più avanti, per l'APK pubblico, potremo decidere
     * se lasciarlo vuoto.
     */
    private const val DEFAULT_BASE_URL =
        ""

    @Volatile
    var baseUrl: String =
        DEFAULT_BASE_URL
        private set


    fun init(
        context: Context
    ) {

        val prefs =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        baseUrl =
            normalizeUrl(
                prefs.getString(
                    KEY_BASE_URL,
                    DEFAULT_BASE_URL
                ) ?: DEFAULT_BASE_URL
            )
    }


    fun saveBaseUrl(
        context: Context,
        url: String
    ) {

        val normalized =
            normalizeUrl(url)

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_BASE_URL,
                normalized
            )
            .apply()

        baseUrl =
            normalized
    }


    private fun normalizeUrl(
        url: String
    ): String {

        return url
            .trim()
            .trimEnd('/')
    }
}


