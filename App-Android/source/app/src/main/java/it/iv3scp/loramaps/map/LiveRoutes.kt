package it.iv3scp.loramaps.map

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import it.iv3scp.loramaps.api.LivePacket
import it.iv3scp.loramaps.model.AprsStation
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/*
 * ============================================================
 * ANIMAZIONE PACCHETTO LIVE
 * ============================================================
 */

fun animateLivePacket(
    map: MapView,
    packet: LivePacket,
    stationIndex: Map<String, AprsStation>
) {

    val points =
        mutableListOf<GeoPoint>()


    /*
     * ========================================================
     * COSTRUZIONE PERCORSO
     *
     * Usiamo radio_path restituito direttamente dall'API:
     *
     * TX -> DIGI -> RX
     *
     * oppure:
     *
     * TX -> RX
     * ========================================================
     */

    packet.radioPath.forEachIndexed { index, callsign ->

        val station =
            stationIndex[
                callsign.uppercase()
            ]

        if (station != null) {

            points.add(
                GeoPoint(
                    station.latitude,
                    station.longitude
                )
            )

        } else if (
            index == 0 &&
            packet.latitude != null &&
            packet.longitude != null
        ) {

            /*
             * Se la stazione trasmittente non è
             * nell'indice ma il pacchetto LIVE
             * contiene le coordinate, usiamo quelle.
             */

            points.add(
                GeoPoint(
                    packet.latitude,
                    packet.longitude
                )
            )
        }
    }


    /*
     * Se radio_path per qualche motivo non fosse
     * disponibile, proviamo almeno TX -> RX.
     */

    if (points.size < 2) {

        points.clear()


        val tx =
            stationIndex[
                packet.callsign.uppercase()
            ]


        if (tx != null) {

            points.add(
                GeoPoint(
                    tx.latitude,
                    tx.longitude
                )
            )

        } else if (
            packet.latitude != null &&
            packet.longitude != null
        ) {

            points.add(
                GeoPoint(
                    packet.latitude,
                    packet.longitude
                )
            )
        }


        val rx =
            stationIndex[
                packet.receiver.uppercase()
            ]


        if (rx != null) {

            points.add(
                GeoPoint(
                    rx.latitude,
                    rx.longitude
                )
            )
        }
    }


    /*
     * Senza almeno due punti non possiamo
     * visualizzare la trasmissione.
     */

    if (points.size < 2) {
        return
    }


    /*
     * ========================================================
     * LINEA ROSSA LIVE
     * ========================================================
     */

    val glow =
        Polyline()


    glow.setPoints(
        points
    )


    glow.outlinePaint.color =
        Color.argb(
            90,
            255,
            0,
            0
        )


    glow.outlinePaint.strokeWidth =
        14f


    glow.outlinePaint.isAntiAlias =
        true


    /*
     * Linea centrale.
     */

    val core =
        Polyline()


    core.setPoints(
        points
    )


    core.outlinePaint.color =
        Color.RED


    core.outlinePaint.strokeWidth =
        6f


    core.outlinePaint.isAntiAlias =
        true


    /*
     * Inseriamo le linee sotto i marker APRS.
     */

    map.overlays.add(
        0,
        glow
    )


    map.overlays.add(
        1,
        core
    )


    /*
     * ========================================================
     * PALLINO MOBILE
     * ========================================================
     */

    val movingDot =
        Marker(map)


    movingDot.position =
        points.first()


    movingDot.setAnchor(
        Marker.ANCHOR_CENTER,
        Marker.ANCHOR_CENTER
    )


    movingDot.icon =
        createLiveDotDrawable(
            map
        )


    /*
     * Il pallino deve stare sopra alle linee.
     */

    map.overlays.add(
        movingDot
    )


    map.invalidate()


    /*
     * ========================================================
     * ANIMAZIONE
     *
     * Durata circa 2.3 secondi,
     * come l'effetto della web map.
     * ========================================================
     */

    val handler =
        Handler(
            Looper.getMainLooper()
        )


    val duration =
        2300L


    val frameDelay =
        30L


    val startTime =
        System.currentTimeMillis()


    val runnable =
        object : Runnable {

            override fun run() {

                val elapsed =
                    System.currentTimeMillis() -
                    startTime


                val progress =
                    (
                        elapsed.toDouble() /
                        duration.toDouble()
                    )
                        .coerceIn(
                            0.0,
                            1.0
                        )


                movingDot.position =
                    interpolateRoute(
                        points,
                        progress
                    )


                map.invalidate()


                if (progress < 1.0) {

                    handler.postDelayed(
                        this,
                        frameDelay
                    )

                } else {

                    /*
                     * Il pallino ha raggiunto
                     * il ricevitore.
                     *
                     * Manteniamo la linea visibile
                     * ancora per un breve istante.
                     */

                    handler.postDelayed(
                        {

                            map.overlays.remove(
                                movingDot
                            )

                            map.overlays.remove(
                                core
                            )

                            map.overlays.remove(
                                glow
                            )

                            map.invalidate()

                        },
                        700L
                    )
                }
            }
        }


    handler.post(
        runnable
    )
}


/*
 * ============================================================
 * INTERPOLAZIONE LUNGO IL PERCORSO
 * ============================================================
 */

private fun interpolateRoute(
    points: List<GeoPoint>,
    progress: Double
): GeoPoint {

    if (points.size == 2) {

        return interpolatePoint(
            points[0],
            points[1],
            progress
        )
    }


    /*
     * Per percorsi con più segmenti
     * distribuiamo l'animazione sulla
     * lunghezza geografica approssimata.
     */

    val lengths =
        mutableListOf<Double>()


    var totalLength =
        0.0


    for (
        i in 0 until points.size - 1
    ) {

        val length =
            distanceSquared(
                points[i],
                points[i + 1]
            )


        lengths.add(
            length
        )


        totalLength +=
            length
    }


    if (totalLength <= 0.0) {

        return points.last()
    }


    val target =
        totalLength *
        progress


    var travelled =
        0.0


    for (
        i in lengths.indices
    ) {

        val segment =
            lengths[i]


        if (
            target <=
            travelled + segment
        ) {

            val localProgress =
                if (
                    segment <= 0.0
                ) {

                    1.0

                } else {

                    (
                        target -
                        travelled
                    ) /
                    segment
                }


            return interpolatePoint(
                points[i],
                points[i + 1],
                localProgress
            )
        }


        travelled +=
            segment
    }


    return points.last()
}


/*
 * ============================================================
 * INTERPOLAZIONE TRA DUE PUNTI
 * ============================================================
 */

private fun interpolatePoint(
    start: GeoPoint,
    end: GeoPoint,
    progress: Double
): GeoPoint {

    val lat =
        start.latitude +
        (
            end.latitude -
            start.latitude
        ) *
        progress


    val lon =
        start.longitude +
        (
            end.longitude -
            start.longitude
        ) *
        progress


    return GeoPoint(
        lat,
        lon
    )
}


/*
 * ============================================================
 * LUNGHEZZA APPROSSIMATA SEGMENTO
 * ============================================================
 */

private fun distanceSquared(
    a: GeoPoint,
    b: GeoPoint
): Double {

    val lat =
        b.latitude -
        a.latitude


    val lon =
        b.longitude -
        a.longitude


    return (
        lat * lat +
        lon * lon
    )
}


/*
 * ============================================================
 * PALLINO ROSSO LIVE
 * ============================================================
 */

private fun createLiveDotDrawable(
    map: MapView
): android.graphics.drawable.Drawable {

    val size =
        24


    val bitmap =
        android.graphics.Bitmap.createBitmap(
            size,
            size,
            android.graphics.Bitmap.Config.ARGB_8888
        )


    val canvas =
        android.graphics.Canvas(
            bitmap
        )


    /*
     * Alone esterno.
     */

    val glowPaint =
        android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        )


    glowPaint.color =
        Color.argb(
            100,
            255,
            0,
            0
        )


    canvas.drawCircle(
        size / 2f,
        size / 2f,
        11f,
        glowPaint
    )


    /*
     * Pallino centrale.
     */

    val dotPaint =
        android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        )


    dotPaint.color =
        Color.RED


    canvas.drawCircle(
        size / 2f,
        size / 2f,
        6f,
        dotPaint
    )


    return android.graphics.drawable.BitmapDrawable(
        map.context.resources,
        bitmap
    )
}
