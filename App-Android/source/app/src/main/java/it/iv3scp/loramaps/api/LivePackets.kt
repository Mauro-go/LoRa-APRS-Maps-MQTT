package it.iv3scp.loramaps.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LivePacket(
    val id: Long,
    val timestamp: String,
    val callsign: String,
    val receiver: String,
    val type: String,
    val via: List<String>,
    val radioPath: List<String>,
    val latitude: Double?,
    val longitude: Double?,
    val symbolTable: String?,
    val symbolCode: String?
)

data class LiveResponse(
    val afterId: Long,
    val newestId: Long,
    val packets: List<LivePacket>
)

object LivePackets {

    private val BASE_URL: String
        get() = ServerConfig.baseUrl

    fun fetch(afterId: Long): LiveResponse {

        val url =
            URL(
                "$BASE_URL/live?after_id=$afterId"
            )

        val connection =
            url.openConnection()
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

            val response =
                connection
                    .inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }

            val json =
                JSONObject(response)

            val packetsJson =
                json.getJSONArray("packets")

            val packets =
                mutableListOf<LivePacket>()

            for (
                i in 0 until packetsJson.length()
            ) {

                val packetJson =
                    packetsJson.getJSONObject(i)

                val via =
                    mutableListOf<String>()

                val viaJson =
                    packetJson.optJSONArray("via")

                if (viaJson != null) {

                    for (
                        v in 0 until viaJson.length()
                    ) {
                        via.add(
                            viaJson.getString(v)
                        )
                    }
                }

                val radioPath =
                    mutableListOf<String>()

                val radioPathJson =
                    packetJson.optJSONArray(
                        "radio_path"
                    )

                if (radioPathJson != null) {

                    for (
                        p in 0 until radioPathJson.length()
                    ) {
                        radioPath.add(
                            radioPathJson.getString(p)
                        )
                    }
                }

                val latitude =
                    if (
                        packetJson.has("latitude") &&
                        !packetJson.isNull("latitude")
                    ) {
                        packetJson.getDouble("latitude")
                    } else {
                        null
                    }

                val longitude =
                    if (
                        packetJson.has("longitude") &&
                        !packetJson.isNull("longitude")
                    ) {
                        packetJson.getDouble("longitude")
                    } else {
                        null
                    }

                val symbolTable =
                    if (
                        packetJson.has("symbol_table") &&
                        !packetJson.isNull("symbol_table")
                    ) {
                        packetJson.getString(
                            "symbol_table"
                        )
                    } else {
                        null
                    }

                val symbolCode =
                    if (
                        packetJson.has("symbol_code") &&
                        !packetJson.isNull("symbol_code")
                    ) {
                        packetJson.getString(
                            "symbol_code"
                        )
                    } else {
                        null
                    }

                packets.add(
                    LivePacket(
                        id =
                            packetJson.getLong("id"),

                        timestamp =
                            packetJson.optString(
                                "timestamp",
                                ""
                            ),

                        callsign =
                            packetJson.optString(
                                "callsign",
                                "?"
                            ),

                        receiver =
                            packetJson.optString(
                                "receiver",
                                "?"
                            ),

                        type =
                            packetJson.optString(
                                "type",
                                "?"
                            ),

                        via =
                            via,

                        radioPath =
                            radioPath,

                        latitude =
                            latitude,

                        longitude =
                            longitude,

                        symbolTable =
                            symbolTable,

                        symbolCode =
                            symbolCode
                    )
                )
            }

            return LiveResponse(
                afterId =
                    json.optLong(
                        "after_id",
                        afterId
                    ),

                newestId =
                    json.optLong(
                        "newest_id",
                        afterId
                    ),

                packets =
                    packets
            )

        } finally {

            connection.disconnect()
        }
    }
}

