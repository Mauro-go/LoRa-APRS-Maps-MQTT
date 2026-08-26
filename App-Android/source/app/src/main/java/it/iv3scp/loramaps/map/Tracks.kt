package it.iv3scp.loramaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import it.iv3scp.loramaps.api.TrackPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt


class TrackOverlays {

    val lines =
        mutableListOf<Polyline>()

    val points =
        mutableListOf<Marker>()
}


data class DisplayTrackPoint(
    val point: TrackPoint,
    val displaySpeedKmh: Double?,
    val speedSource: String?
)


/* ============================================================
   CANCELLA TRACCE
   ============================================================ */

fun clearTracks(
    map: MapView,
    overlays: TrackOverlays
) {

    overlays.lines.forEach {
        map.overlays.remove(it)
    }

    overlays.points.forEach {
        map.overlays.remove(it)
    }

    overlays.lines.clear()
    overlays.points.clear()

    map.invalidate()
}


/* ============================================================
   DISEGNA TUTTE LE TRACCE
   ============================================================ */

fun drawAllTracks(
    map: MapView,
    tracks: Map<String, List<TrackPoint>>,
    overlays: TrackOverlays
) {

    clearTracks(
        map,
        overlays
    )


    tracks.forEach { (callsign, trackPoints) ->

        /*
         * Ordine temporale.
         */

        val ordered =
            trackPoints.sortedBy {
                it.packetId
            }


        /*
         * Come sul sito:
         * eliminiamo soltanto posizioni
         * consecutive identiche.
         */

        val unique =
            mutableListOf<TrackPoint>()


        ordered.forEach { point ->

            val previous =
                unique.lastOrNull()


            if (
                previous != null &&
                previous.latitude == point.latitude &&
                previous.longitude == point.longitude
            ) {
                return@forEach
            }


            unique.add(
                point
            )
        }


        if (unique.size <= 1) {
            return@forEach
        }


        /*
         * ====================================================
         * VELOCITÀ
         * ====================================================
         *
         * 1. usa speed_kmh APRS se presente
         * 2. altrimenti calcola distanza / tempo
         */

        val displayPoints =
            mutableListOf<DisplayTrackPoint>()


        unique.forEachIndexed { index, point ->

            val aprsSpeed =
                point.speedKmh


            if (aprsSpeed != null) {

                displayPoints.add(
                    DisplayTrackPoint(
                        point = point,
                        displaySpeedKmh = aprsSpeed,
                        speedSource = "APRS"
                    )
                )

            } else if (index > 0) {

                val calculated =
                    calculateTrackSpeedKmh(
                        unique[index - 1],
                        point
                    )


                displayPoints.add(
                    DisplayTrackPoint(
                        point = point,
                        displaySpeedKmh = calculated,
                        speedSource =
                            if (calculated != null) {
                                "CALCULATED"
                            } else {
                                null
                            }
                    )
                )

            } else {

                displayPoints.add(
                    DisplayTrackPoint(
                        point = point,
                        displaySpeedKmh = null,
                        speedSource = null
                    )
                )
            }
        }


        /*
         * ====================================================
         * LINEA BLU
         * ====================================================
         */

        val line =
            Polyline()


        line.setPoints(
            unique.map { point ->

                GeoPoint(
                    point.latitude,
                    point.longitude
                )
            }
        )


        line.outlinePaint.color =
            Color.rgb(
                0,
                100,
                230
            )


        line.outlinePaint.strokeWidth =
            10f


        line.outlinePaint.alpha =
            230


        line.outlinePaint.isAntiAlias =
            true


        map.overlays.add(
            0,
            line
        )


        overlays.lines.add(
            line
        )


        /*
         * ====================================================
         * PUNTI DELLA TRACCIA
         * ====================================================
         */

        displayPoints.forEach { displayPoint ->

            val point =
                displayPoint.point


            val marker =
                Marker(map)


            marker.position =
                GeoPoint(
                    point.latitude,
                    point.longitude
                )


            marker.setAnchor(
                Marker.ANCHOR_CENTER,
                Marker.ANCHOR_CENTER
            )


            marker.icon =
                createTrackPointIcon(
                    map
                )


            marker.title =
                callsign


            val text =
                StringBuilder()


            /*
             * Ora
             */

            text.append(
                "Ora: "
            )

            text.append(
                formatTrackTime(
                    point.timestamp
                )
            )


            /*
             * Ricevitore
             */

            text.append(
                "\nRicevuto da: "
            )

            text.append(
                point.receiver
            )


            /*
             * DIRECT / VIA
             */

            if (
                point.via.isNotEmpty()
            ) {

                text.append(
                    "\nVia: "
                )

                text.append(
                    point.via.joinToString(
                        " → "
                    )
                )

            } else {

                text.append(
                    "\nDiretta"
                )
            }


            /*
             * Velocità
             */

            val speed =
                displayPoint.displaySpeedKmh


            if (
                speed != null &&
                speed > 1.0
            ) {

                text.append(
                    "\nVelocità: "
                )

                text.append(
                    String.format(
                        Locale.US,
                        "%.1f km/h",
                        speed
                    )
                )
            }


            marker.snippet =
                text.toString()


            /*
             * Click sul punto.
             */

            marker.setOnMarkerClickListener {

                    clicked,
                    mapView ->


                mapView.overlays
                    .filterIsInstance<Marker>()
                    .forEach { other ->

                        if (
                            other != clicked &&
                            other.isInfoWindowShown
                        ) {

                            other.closeInfoWindow()
                        }
                    }


                clicked.showInfoWindow()


                true
            }


            map.overlays.add(
                marker
            )


            overlays.points.add(
                marker
            )
        }
    }


    map.invalidate()
}


/* ============================================================
   CALCOLA VELOCITÀ TRA DUE PUNTI
   ============================================================ */

private fun calculateTrackSpeedKmh(
    previous: TrackPoint,
    current: TrackPoint
): Double? {

    val previousTime =
        parseTimestampMillis(
            previous.timestamp
        )
            ?: return null


    val currentTime =
        parseTimestampMillis(
            current.timestamp
        )
            ?: return null


    val elapsedMs =
        currentTime -
                previousTime


    if (elapsedMs <= 0L) {
        return null
    }


    /*
     * Distanza geografica in km.
     */

    val distanceKm =
        haversineKm(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude
        )


    val elapsedHours =
        elapsedMs.toDouble() /
                3_600_000.0


    if (elapsedHours <= 0.0) {
        return null
    }


    val speed =
        distanceKm /
                elapsedHours


    if (
        !speed.isFinite() ||
        speed < 0.0
    ) {
        return null
    }


    return speed
}


/* ============================================================
   DISTANZA HAVERSINE
   ============================================================ */

private fun haversineKm(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {

    val earthRadiusKm =
        6371.0088


    val dLat =
        Math.toRadians(
            lat2 - lat1
        )


    val dLon =
        Math.toRadians(
            lon2 - lon1
        )


    val rLat1 =
        Math.toRadians(
            lat1
        )


    val rLat2 =
        Math.toRadians(
            lat2
        )


    val a =
        sin(dLat / 2) *
                sin(dLat / 2) +
                cos(rLat1) *
                cos(rLat2) *
                sin(dLon / 2) *
                sin(dLon / 2)


    val c =
        2 *
                atan2(
                    sqrt(a),
                    sqrt(1 - a)
                )


    return earthRadiusKm *
            c
}


/* ============================================================
   TIMESTAMP -> MILLISECONDI
   ============================================================ */

private fun parseTimestampMillis(
    value: String
): Long? {

    if (value.isBlank()) {
        return null
    }


    return try {

        /*
         * API:
         *
         * 2026-08-25T18:49:32.502766+00:00
         *
         * SimpleDateFormat gestisce millisecondi,
         * quindi riduciamo la frazione a 3 cifre.
         */

        var normalized =
            value.trim()


        /*
         * Converte +00:00 in +0000.
         */

        normalized =
            normalized.replace(
                Regex(
                    "([+-]\\d\\d):(\\d\\d)$"
                ),
                "$1$2"
            )


        /*
         * Riduce microsecondi a millisecondi.
         */

        normalized =
            normalized.replace(
                Regex(
                    "\\.(\\d{3})\\d+"
                ),
                ".$1"
            )


        val format =
            SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                Locale.US
            )


        format.timeZone =
            TimeZone.getTimeZone(
                "UTC"
            )


        format.parse(
            normalized
        )?.time


    } catch (e: Exception) {

        null
    }
}


/* ============================================================
   ICONA PUNTO TRACCIA
   ============================================================ */

private fun createTrackPointIcon(
    map: MapView
): BitmapDrawable {

    val size =
        64


    val bitmap =
        Bitmap.createBitmap(
            size,
            size,
            Bitmap.Config.ARGB_8888
        )


    val canvas =
        Canvas(bitmap)


    /*
     * Alone.
     */

    val glowPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        )


    glowPaint.color =
        Color.argb(
            80,
            0,
            100,
            230
        )


    canvas.drawCircle(
        size / 2f,
        size / 2f,
        24f,
        glowPaint
    )


    /*
     * Bordo blu.
     */

    val outerPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        )


    outerPaint.color =
        Color.rgb(
            0,
            100,
            230
        )


    canvas.drawCircle(
        size / 2f,
        size / 2f,
        17f,
        outerPaint
    )


    /*
     * Centro bianco.
     */

    val innerPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        )


    innerPaint.color =
        Color.WHITE


    canvas.drawCircle(
        size / 2f,
        size / 2f,
        10f,
        innerPaint
    )


    return BitmapDrawable(
        map.context.resources,
        bitmap
    )
}


/* ============================================================
   DATA / ORA VISUALIZZATA
   ============================================================ */

private fun formatTrackTime(
    value: String
): String {

    if (
        value.isBlank()
    ) {
        return "-"
    }


    return try {

        val clean =
            value
                .substringBefore(".")
                .substringBefore("+")


        val parts =
            clean.split("T")


        if (
            parts.size != 2
        ) {

            value

        } else {

            val date =
                parts[0].split("-")


            if (
                date.size != 3
            ) {

                value

            } else {

                "${date[2]}/${date[1]}/${date[0]} ${parts[1]}"
            }
        }


    } catch (e: Exception) {

        value
    }
}
