package it.iv3scp.loramaps.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TrackPoint(
    val packetId: Long,
    val timestamp: String,
    val latitude: Double,
    val longitude: Double,
    val receiver: String,
    val type: String,
    val via: List<String>,
    val radioPath: List<String>,
    val speedKmh: Double?
)

object TracksApi {

    private val BASE_URL: String
        get() = ServerConfig.baseUrl

    fun loadTracks(
        hours: Int
    ): Map<String, List<TrackPoint>> {

        val connection =
            URL(
                "$BASE_URL/tracks?hours=$hours"
            ).openConnection()
                    as HttpURLConnection

        try {

            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
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

            val root =
                JSONObject(response)

            val tracksJson =
                root.getJSONObject("tracks")

            val result =
                mutableMapOf<
                    String,
                    List<TrackPoint>
                >()

            val callsigns =
                tracksJson.keys()

            while (
                callsigns.hasNext()
            ) {

                val callsign =
                    callsigns.next()

                val pointsJson =
                    tracksJson.getJSONArray(
                        callsign
                    )

                val points =
                    mutableListOf<TrackPoint>()

                for (
                    i in 0 until pointsJson.length()
                ) {

                    val pointJson =
                        pointsJson.getJSONObject(i)

                    val via =
                        mutableListOf<String>()

                    val viaJson =
                        pointJson.optJSONArray(
                            "via"
                        )

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
                        pointJson.optJSONArray(
                            "radio_path"
                        )

                    if (
                        radioPathJson != null
                    ) {

                        for (
                            p in 0 until radioPathJson.length()
                        ) {

                            radioPath.add(
                                radioPathJson.getString(p)
                            )
                        }
                    }


                    val speed =
                        if (
                            pointJson.has(
                                "speed_kmh"
                            ) &&
                            !pointJson.isNull(
                                "speed_kmh"
                            )
                        ) {

                            pointJson.getDouble(
                                "speed_kmh"
                            )

                        } else {

                            null
                        }


                    points.add(

                        TrackPoint(

                            packetId =
                                pointJson.getLong(
                                    "packet_id"
                                ),

                            timestamp =
                                pointJson.optString(
                                    "timestamp",
                                    ""
                                ),

                            latitude =
                                pointJson.getDouble(
                                    "latitude"
                                ),

                            longitude =
                                pointJson.getDouble(
                                    "longitude"
                                ),

                            receiver =
                                pointJson.optString(
                                    "receiver",
                                    "?"
                                ),

                            type =
                                pointJson.optString(
                                    "type",
                                    "?"
                                ),

                            via =
                                via,

                            radioPath =
                                radioPath,

                            speedKmh =
                                speed
                        )
                    )
                }


                /*
                 * Sicurezza:
                 * manteniamo sempre i punti
                 * in ordine temporale.
                 */

                result[
                    callsign.uppercase()
                ] =
                    points.sortedBy {
                        it.packetId
                    }
            }


            return result


        } finally {

            connection.disconnect()
        }
    }
}

