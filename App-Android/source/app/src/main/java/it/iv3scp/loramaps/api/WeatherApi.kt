package it.iv3scp.loramaps.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder


data class AprsWeather(

    val temperatureC: Double?,

    val humidity: Int?,

    val pressureHpa: Double?,

    val windDirection: Int?,

    val windSpeedKmh: Double?,

    val gustKmh: Double?,

    val rainHourMm: Double?,

    val rain24Mm: Double?,

    val rainMidnightMm: Double?,

    val timestamp: String?
)


object WeatherApi {

    private val BASE_URL: String
        get() =
            ServerConfig.baseUrl


    /*
     * ========================================================
     * CARICA ULTIMO METEO DELLA STAZIONE
     * ========================================================
     *
     * Usa lo stesso endpoint e lo stesso criterio della
     * pagina station.html:
     *
     * /station?callsign=XXX&hours=N
     *
     * source_packets arriva dal più recente.
     * Il primo pacchetto contenente dati meteo validi
     * viene utilizzato.
     */

    fun loadWeather(
        callsign: String,
        hours: Int
    ): AprsWeather? {

        val encodedCallsign =
            URLEncoder.encode(
                callsign,
                "UTF-8"
            )


        val response =
            get(
                "$BASE_URL/station" +
                "?callsign=$encodedCallsign" +
                "&hours=$hours"
            )


        val json =
            JSONObject(
                response
            )


        val packets =
            json.optJSONArray(
                "source_packets"
            )
                ?: return null


        for (
            i in 0 until packets.length()
        ) {

            val packet =
                packets.optJSONObject(
                    i
                )
                    ?: continue


            val weather =
                parseWeather(
                    packet
                )


            if (
                weather != null
            ) {

                return weather
            }
        }


        return null
    }


    /*
     * ========================================================
     * PARSER METEO APRS
     * ========================================================
     *
     * tDDD  temperatura Fahrenheit
     * hDD   umidita %
     * bDDDDD pressione decimi hPa
     * dDDD  direzione vento
     * sDDD  vento mph
     * gDDD  raffica mph
     * rDDD  pioggia ultima ora, centesimi di pollice
     * pDDD  pioggia 24 ore
     * PDDD  pioggia da mezzanotte
     */

    private fun parseWeather(
        packet: JSONObject
    ): AprsWeather? {


        val raw =
            listOf(
                packet.optString(
                    "packet",
                    ""
                ),

                packet.optString(
                    "raw",
                    ""
                ),

                packet.optString(
                    "data",
                    ""
                )
            )
                .firstOrNull {
                    it.isNotBlank()
                }
                ?: return null


        val payload =
            if (
                raw.contains(":")
            ) {

                raw.substringAfter(
                    ":"
                )

            } else {

                raw
            }


        var temperatureC:
                Double? =
            null

        var humidity:
                Int? =
            null

        var pressureHpa:
                Double? =
            null

        var windDirection:
                Int? =
            null

        var windSpeedKmh:
                Double? =
            null

        var gustKmh:
                Double? =
            null

        var rainHourMm:
                Double? =
            null

        var rain24Mm:
                Double? =
            null

        var rainMidnightMm:
                Double? =
            null


        /*
         * TEMPERATURA
         */

        Regex(
            """t(-?\d{3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { fahrenheit ->

                if (
                    fahrenheit != 999
                ) {

                    temperatureC =
                        (
                            fahrenheit -
                            32
                        ) *
                        5.0 /
                        9.0
                }
            }


        /*
         * UMIDITA
         *
         * APRS:
         * h00 / h000 = 100 %
         */

        Regex(
            """h(\d{2,3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { value ->

                humidity =
                    when {

                        value == 0 ->
                            100

                        value in 1..100 ->
                            value

                        else ->
                            null
                    }
            }


        /*
         * PRESSIONE
         */

        Regex(
            """b(\d{5})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toDoubleOrNull()
            ?.let { value ->

                val pressure =
                    value /
                    10.0

                if (
                    pressure > 800.0 &&
                    pressure < 1200.0
                ) {

                    pressureHpa =
                        pressure
                }
            }


        /*
         * DIREZIONE VENTO
         */

        Regex(
            """d(\d{3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { value ->

                if (
                    value in 0..360
                ) {

                    windDirection =
                        value
                }
            }


        /*
         * VELOCITA VENTO
         */

        Regex(
            """s(\d{3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { mph ->

                if (
                    mph != 999
                ) {

                    windSpeedKmh =
                        mph *
                        1.609344
                }
            }


        /*
         * RAFFICA
         */

        Regex(
            """g(\d{3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { mph ->

                if (
                    mph != 999
                ) {

                    gustKmh =
                        mph *
                        1.609344
                }
            }


        /*
         * PIOGGIA ULTIMA ORA
         */

        Regex(
            """r(\d{3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { value ->

                if (
                    value != 999
                ) {

                    rainHourMm =
                        value *
                        0.254
                }
            }


        /*
         * PIOGGIA 24 ORE
         */

        Regex(
            """p(\d{3})""",
            RegexOption.IGNORE_CASE
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { value ->

                if (
                    value != 999
                ) {

                    rain24Mm =
                        value *
                        0.254
                }
            }


        /*
         * PIOGGIA DA MEZZANOTTE
         *
         * Qui P maiuscola e significativa.
         */

        Regex(
            """P(\d{3})"""
        )
            .find(
                payload
            )
            ?.groupValues
            ?.getOrNull(
                1
            )
            ?.toIntOrNull()
            ?.let { value ->

                if (
                    value != 999
                ) {

                    rainMidnightMm =
                        value *
                        0.254
                }
            }


        /*
         * Nessun dato meteo trovato.
         */

        if (
            temperatureC == null &&
            humidity == null &&
            pressureHpa == null &&
            windDirection == null &&
            windSpeedKmh == null &&
            gustKmh == null &&
            rainHourMm == null &&
            rain24Mm == null &&
            rainMidnightMm == null
        ) {

            return null
        }


        val timestamp =
            listOf(
                packet.optString(
                    "timestamp",
                    ""
                ),

                packet.optString(
                    "received_at",
                    ""
                ),

                packet.optString(
                    "time",
                    ""
                )
            )
                .firstOrNull {
                    it.isNotBlank()
                }


        return AprsWeather(

            temperatureC =
                temperatureC,

            humidity =
                humidity,

            pressureHpa =
                pressureHpa,

            windDirection =
                windDirection,

            windSpeedKmh =
                windSpeedKmh,

            gustKmh =
                gustKmh,

            rainHourMm =
                rainHourMm,

            rain24Mm =
                rain24Mm,

            rainMidnightMm =
                rainMidnightMm,

            timestamp =
                timestamp
        )
    }


    /*
     * ========================================================
     * HTTP GET
     * ========================================================
     */

    private fun get(
        urlString: String
    ): String {

        val connection =
            URL(
                urlString
            )
                .openConnection()
                    as HttpURLConnection


        try {

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            connection.useCaches =
                false


            if (
                connection.responseCode !in
                200..299
            ) {

                throw Exception(
                    "HTTP ${connection.responseCode}"
                )
            }


            return connection
                .inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }


        } finally {

            connection.disconnect()
        }
    }
}