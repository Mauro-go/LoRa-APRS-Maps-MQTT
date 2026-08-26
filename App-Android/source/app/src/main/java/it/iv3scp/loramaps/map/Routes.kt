package it.iv3scp.loramaps.map

import android.graphics.Color
import it.iv3scp.loramaps.model.AprsStation
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

fun clearRouteLines(
    map: MapView,
    activeRouteLines: MutableList<Polyline>
) {

    activeRouteLines.forEach { line ->
        map.overlays.remove(line)
    }

    activeRouteLines.clear()

    map.invalidate()
}


fun drawStationRoutes(
    map: MapView,
    station: AprsStation,
    stationIndex: Map<String, AprsStation>,
    activeRouteLines: MutableList<Polyline>
) {

    station.receptions.forEach { reception ->

        val points =
            mutableListOf<GeoPoint>()

        /*
         * Punto di partenza:
         * stazione selezionata.
         */

        points.add(
            GeoPoint(
                station.latitude,
                station.longitude
            )
        )

        /*
         * Se la ricezione è VIA,
         * aggiungiamo i digipeater
         * nell'ordine del percorso.
         */

        if (
            reception.type.equals(
                "VIA",
                ignoreCase = true
            )
        ) {

            reception.via.forEach { digiCall ->

                val digi =
                    stationIndex[
                        digiCall.uppercase()
                    ]

                if (digi != null) {

                    points.add(
                        GeoPoint(
                            digi.latitude,
                            digi.longitude
                        )
                    )
                }
            }
        }

        /*
         * Ricevitore finale.
         */

        val receiver =
            stationIndex[
                reception.receiver.uppercase()
            ]

        if (receiver != null) {

            points.add(
                GeoPoint(
                    receiver.latitude,
                    receiver.longitude
                )
            )
        }

        /*
         * Senza almeno due coordinate
         * non possiamo tracciare una linea.
         */

        if (points.size < 2) {
            return@forEach
        }

        val line =
            Polyline()

        line.setPoints(points)

        /*
         * Stile attuale:
         * linea rossa ben visibile.
         */

        line.outlinePaint.color =
            Color.RED

        line.outlinePaint.strokeWidth =
            7f

        line.outlinePaint.isAntiAlias =
            true

        /*
         * La linea viene inserita sotto
         * ai marker APRS.
         */

        map.overlays.add(
            0,
            line
        )

        activeRouteLines.add(
            line
        )
    }

    map.invalidate()
}
