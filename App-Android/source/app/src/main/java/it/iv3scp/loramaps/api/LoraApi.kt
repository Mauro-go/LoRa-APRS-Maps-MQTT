package it.iv3scp.loramaps.api

import it.iv3scp.loramaps.model.AprsStation
import it.iv3scp.loramaps.model.Reception
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object LoraApi {

    private val BASE_URL: String
        get() = ServerConfig.baseUrl

    fun loadStations(hours: Int): List<AprsStation> {

        val response =
            get("$BASE_URL/map?hours=$hours")

        val json =
            JSONObject(response)

        val stationsJson =
            json.getJSONArray("stations")

        val stations =
            mutableListOf<AprsStation>()

        for (i in 0 until stationsJson.length()) {

            val stationJson =
                stationsJson.getJSONObject(i)

            if (
                stationJson.isNull("latitude") ||
                stationJson.isNull("longitude")
            ) {
                continue
            }

            val receptions =
                mutableListOf<Reception>()

            val receptionsJson =
                stationJson.optJSONArray("receptions")

            if (receptionsJson != null) {

                for (r in 0 until receptionsJson.length()) {

                    val receptionJson =
                        receptionsJson.getJSONObject(r)

                    val via =
                        mutableListOf<String>()

                    val viaJson =
                        receptionJson.optJSONArray("via")

                    if (viaJson != null) {

                        for (v in 0 until viaJson.length()) {

                            via.add(
                                viaJson.getString(v)
                            )
                        }
                    }

                    val rssi =
                        if (
                            receptionJson.has("rssi") &&
                            !receptionJson.isNull("rssi")
                        ) {
                            receptionJson.getInt("rssi")
                        } else {
                            null
                        }

                    receptions.add(
                        Reception(
                            receiver =
                                receptionJson.optString(
                                    "receiver",
                                    "?"
                                ),

                            type =
                                receptionJson.optString(
                                    "type",
                                    "?"
                                ),

                            via = via,

                            rssi = rssi
                        )
                    )
                }
            }

            stations.add(
                AprsStation(
                    callsign =
                        stationJson.getString("callsign"),

                    latitude =
                        stationJson.getDouble("latitude"),

                    longitude =
                        stationJson.getDouble("longitude"),

                    symbolTable =
                        stationJson.optString(
                            "symbol_table",
                            "/"
                        ),

                    symbolCode =
                        stationJson.optString(
                            "symbol_code",
                            ">"
                        ),

                    info =
                        if (
                            stationJson.has("info") &&
                            !stationJson.isNull("info")
                        ) {
                            stationJson.getString("info")
                        } else {
                            null
                        },

                    lastSeen =
                        if (
                            stationJson.has("last_seen") &&
                            !stationJson.isNull("last_seen")
                        ) {
                            stationJson.getString("last_seen")
                        } else {
                            null
                        },

                    speedKmh =
                        if (
                            stationJson.has("speed_kmh") &&
                            !stationJson.isNull("speed_kmh")
                        ) {
                            stationJson.getDouble("speed_kmh")
                        } else {
                            null
                        },

                    receptions =
                        receptions
                )
            )
        }

        return stations
    }



    /*
     * ========================================================
     * COPERTURA RICEVITORE
     * ========================================================
     *
     * Usa lo stesso endpoint della pagina coverage.html.
     * L'endpoint restituisce i pacchetti ricevuti
     * DIRETTAMENTE dal ricevitore selezionato.
     */

    fun loadCoverage(
        receiver: String,
        hours: Int
    ): CoverageData {

        val response =
            get(
                "$BASE_URL/coverage" +
                "?receiver=" +
                java.net.URLEncoder.encode(
                    receiver,
                    "UTF-8"
                ) +
                "&hours=$hours"
            )

        val json =
            JSONObject(response)

        val receiverJson =
            json.getJSONObject("receiver")


        val coverageReceiver =
            CoverageReceiver(

                callsign =
                    receiverJson.getString(
                        "callsign"
                    ),

                latitude =
                    receiverJson.getDouble(
                        "latitude"
                    ),

                longitude =
                    receiverJson.getDouble(
                        "longitude"
                    ),

                symbolTable =
                    receiverJson.optString(
                        "symbol_table",
                        "/"
                    ),

                symbolCode =
                    receiverJson.optString(
                        "symbol_code",
                        ">"
                    )
            )


        val packetsJson =
            json.getJSONArray(
                "packets"
            )


        /*
         * L'endpoint contiene più pacchetti della stessa
         * stazione. Per la mappa di copertura ci serve
         * una sola posizione per nominativo.
         *
         * I pacchetti arrivano dal più recente:
         * conserviamo quindi il primo valido.
         */

        val stationsByCall =
            linkedMapOf<String, CoverageStation>()


        for (
            i in 0 until packetsJson.length()
        ) {

            val packet =
                packetsJson.getJSONObject(i)


            if (
                !packet.optString(
                    "reception_type"
                ).equals(
                    "DIRECT",
                    ignoreCase = true
                )
            ) {
                continue
            }


            if (
                packet.isNull("latitude") ||
                packet.isNull("longitude")
            ) {
                continue
            }


            val callsign =
                packet.optString(
                    "callsign"
                )


            if (
                callsign.isBlank() ||
                stationsByCall.containsKey(
                    callsign
                )
            ) {
                continue
            }


            val rssi =
                if (
                    packet.has("rssi") &&
                    !packet.isNull("rssi")
                ) {
                    packet.getInt("rssi")
                } else {
                    null
                }


            val info =
                if (
                    packet.has("info") &&
                    !packet.isNull("info")
                ) {
                    packet.getString("info")
                } else {
                    null
                }


            val timestamp =
                if (
                    packet.has("received_at") &&
                    !packet.isNull("received_at")
                ) {
                    packet.getString(
                        "received_at"
                    )
                } else {
                    null
                }


            stationsByCall[callsign] =
                CoverageStation(

                    callsign =
                        callsign,

                    latitude =
                        packet.getDouble(
                            "latitude"
                        ),

                    longitude =
                        packet.getDouble(
                            "longitude"
                        ),

                    symbolTable =
                        packet.optString(
                            "symbol_table",
                            "/"
                        ),

                    symbolCode =
                        packet.optString(
                            "symbol_code",
                            ">"
                        ),

                    info =
                        info,

                    rssi =
                        rssi,

                    timestamp =
                        timestamp
                )
        }


        return CoverageData(

            receiver =
                coverageReceiver,

            hours =
                json.optDouble(
                    "hours",
                    hours.toDouble()
                ),

            stationCount =
                json.optInt(
                    "stations",
                    stationsByCall.size
                ),

            stations =
                stationsByCall.values.toList()
        )
    }


    /*
     * Richiesta HTTP generica.
     *
     * Ci servirà anche per:
     * /live
     * /tracks
     * /receivers
     * dashboard
     * coverage
     */

    private fun get(urlString: String): String {

        val connection =
            URL(urlString).openConnection()
                    as HttpURLConnection

        try {

            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.useCaches = false

            if (
                connection.responseCode !in 200..299
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



