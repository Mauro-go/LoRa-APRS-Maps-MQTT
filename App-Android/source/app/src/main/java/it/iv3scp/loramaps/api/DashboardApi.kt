package it.iv3scp.loramaps.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DashboardStats(
    val totalPackets: Long,
    val uniqueStations: Int,
    val packets24h: Long
)

data class DashboardReceiver(
    val callsign: String,
    val enabled: Boolean,
    val packets: Long,
    val stations: Int,
    val lastPacket: String?
)

data class DashboardStation(
    val callsign: String,
    val packets: Long,
    val lastSeen: String?
)

data class DashboardPacket(
    val callsign: String,
    val receiver: String,
    val timestamp: String?,
    val packet: String?
)

object DashboardApi {

    private val BASE_URL: String
        get() = ServerConfig.baseUrl

    fun loadStats(): DashboardStats {

        val json =
            JSONObject(
                get("$BASE_URL/stats")
            )

        return DashboardStats(
            totalPackets =
                json.optLong(
                    "total_packets",
                    0
                ),

            uniqueStations =
                json.optInt(
                    "unique_stations",
                    0
                ),

            packets24h =
                json.optLong(
                    "packets_24h",
                    0
                )
        )
    }


    fun loadReceivers(): List<DashboardReceiver> {

        val json =
            JSONObject(
                get("$BASE_URL/status")
            )

        val array =
            json.getJSONArray(
                "receivers"
            )

        val result =
            mutableListOf<DashboardReceiver>()

        for (
            i in 0 until array.length()
        ) {

            val item =
                array.getJSONObject(i)

            result.add(
                DashboardReceiver(
                    callsign =
                        item.optString(
                            "callsign",
                            "?"
                        ),

                    enabled =
                        item.optBoolean(
                            "enabled",
                            false
                        ),

                    packets =
                        item.optLong(
                            "packets",
                            0
                        ),

                    stations =
                        item.optInt(
                            "stations",
                            0
                        ),

                    lastPacket =
                        if (
                            item.has("last_packet") &&
                            !item.isNull("last_packet")
                        ) {
                            item.getString(
                                "last_packet"
                            )
                        } else {
                            null
                        }
                )
            )
        }

        return result
    }


    fun loadStations(
        receiver: String
    ): List<DashboardStation> {

        val encoded =
            URLEncoder.encode(
                receiver,
                "UTF-8"
            )

        val json =
            JSONObject(
                get(
                    "$BASE_URL/stations?receiver=$encoded"
                )
            )

        val array =
            json.getJSONArray(
                "stations"
            )

        val result =
            mutableListOf<DashboardStation>()

        for (
            i in 0 until array.length()
        ) {

            val item =
                array.getJSONObject(i)

            result.add(
                DashboardStation(
                    callsign =
                        item.optString(
                            "callsign",
                            "?"
                        ),

                    packets =
                        item.optLong(
                            "packets",
                            0
                        ),

                    lastSeen =
                        if (
                            item.has("last_seen") &&
                            !item.isNull("last_seen")
                        ) {
                            item.getString(
                                "last_seen"
                            )
                        } else {
                            null
                        }
                )
            )
        }

        return result
    }


    fun loadLatestPackets(
        limit: Int = 50
    ): List<DashboardPacket> {

        val json =
            JSONObject(
                get(
                    "$BASE_URL/packets?limit=$limit"
                )
            )

        val array =
            json.getJSONArray(
                "packets"
            )

        val result =
            mutableListOf<DashboardPacket>()

        for (
            i in 0 until array.length()
        ) {

            val item =
                array.getJSONObject(i)

            result.add(
                DashboardPacket(
                    callsign =
                        item.optString(
                            "callsign",
                            "?"
                        ),

                    receiver =
                        item.optString(
                            "receiver_callsign",
                            item.optString(
                                "receiver",
                                "?"
                            )
                        ),

                    timestamp =
                        if (
                            item.has("timestamp") &&
                            !item.isNull("timestamp")
                        ) {
                            item.getString(
                                "timestamp"
                            )
                        } else {
                            null
                        },

                    packet =
                        if (
                            item.has("packet") &&
                            !item.isNull("packet")
                        ) {
                            item.getString(
                                "packet"
                            )
                        } else {
                            null
                        }
                )
            )
        }

        return result
    }


    private fun get(
        urlString: String
    ): String {

        val connection =
            URL(
                urlString
            ).openConnection()
                    as HttpURLConnection

        try {

            connection.requestMethod =
                "GET"

            connection.connectTimeout =
                10000

            connection.readTimeout =
                15000

            connection.useCaches =
                false

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

