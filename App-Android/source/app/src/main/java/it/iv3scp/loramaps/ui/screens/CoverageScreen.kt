package it.iv3scp.loramaps.ui.screens

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.iv3scp.loramaps.api.CoverageData
import it.iv3scp.loramaps.api.LoraApi
import it.iv3scp.loramaps.map.createAprsMarkerBitmap
import it.iv3scp.loramaps.model.AprsStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline


@Composable
fun CoverageScreen(
    stations: List<AprsStation>,
    selectedHours: Int,
    modifier: Modifier = Modifier
) {

    /*
     * I ricevitori vengono scoperti dai dati /map.
     * La copertura invece viene caricata dall'endpoint
     * dedicato /coverage.
     */

    val receivers =
        stations
            .flatMap { station ->
                station.receptions.map {
                    it.receiver
                }
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .sorted()


    var selectedReceiver by remember {
        mutableStateOf<String?>(
            null
        )
    }


    var expanded by remember {
        mutableStateOf(false)
    }


    var coverage by remember {
        mutableStateOf<CoverageData?>(
            null
        )
    }


    var loading by remember {
        mutableStateOf(false)
    }


    var error by remember {
        mutableStateOf<String?>(
            null
        )
    }


    /*
     * Seleziona automaticamente il primo ricevitore
     * disponibile.
     */

    LaunchedEffect(receivers) {

        if (
            selectedReceiver == null &&
            receivers.isNotEmpty()
        ) {

            selectedReceiver =
                receivers.first()
        }
    }


    /*
     * Ricarica la copertura quando cambia:
     *
     * - ricevitore
     * - intervallo temporale
     */

    LaunchedEffect(
        selectedReceiver,
        selectedHours
    ) {

        val receiver =
            selectedReceiver
                ?: return@LaunchedEffect


        loading =
            true

        error =
            null


        try {

            coverage =
                withContext(
                    Dispatchers.IO
                ) {

                    LoraApi.loadCoverage(
                        receiver =
                            receiver,

                        hours =
                            selectedHours
                    )
                }

        } catch (e: kotlinx.coroutines.CancellationException) {

            /*
             * Normale quando la schermata Copertura
             * esce dalla Composition.
             *
             * Non deve essere mostrato come errore.
             */
            throw e

        } catch (e: Exception) {

            coverage =
                null

            error =
                e.message
                    ?: "Errore caricamento copertura"

        } finally {

            loading =
                false
        }
    }


    Box(
        modifier =
            modifier.fillMaxSize()
    ) {

        /*
         * ====================================================
         * MAPPA
         * ====================================================
         */

        AndroidView(

            modifier =
                Modifier.fillMaxSize(),

            factory = { context ->

                MapView(context).apply {

                    setTileSource(
                        TileSourceFactory.MAPNIK
                    )

                    setMultiTouchControls(
                        true
                    )

                    controller.setZoom(
                        9.0
                    )

                    controller.setCenter(
                        GeoPoint(
                            45.90,
                            13.65
                        )
                    )
                }
            },

            update = { map ->

                map.overlays.clear()


                val data =
                    coverage
                        ?: return@AndroidView


                val receiver =
                    data.receiver


                /*
                 * =================================================
                 * LINEE RICEVITORE -> STAZIONE
                 * =================================================
                 */

                data.stations.forEach { station ->

                    val line =
                        Polyline()


                    line.setPoints(
                        listOf(

                            GeoPoint(
                                receiver.latitude,
                                receiver.longitude
                            ),

                            GeoPoint(
                                station.latitude,
                                station.longitude
                            )
                        )
                    )


                    line.outlinePaint.color =
                        Color.rgb(
                            0,
                            100,
                            230
                        )


                    line.outlinePaint.strokeWidth =
                        5f


                    line.outlinePaint.alpha =
                        180


                    line.outlinePaint.isAntiAlias =
                        true


                    map.overlays.add(
                        line
                    )
                }


                /*
                 * =================================================
                 * STAZIONI DIRETTE
                 * =================================================
                 */

                data.stations.forEach { station ->

                    /*
                     * createAprsMarkerBitmap lavora con AprsStation.
                     * Creiamo quindi una rappresentazione minima
                     * della stazione ricevuta.
                     */

                    val aprsStation =
                        AprsStation(

                            callsign =
                                station.callsign,

                            latitude =
                                station.latitude,

                            longitude =
                                station.longitude,

                            symbolTable =
                                station.symbolTable,

                            symbolCode =
                                station.symbolCode,

                            info =
                                station.info,

                            lastSeen =
                                station.timestamp,

                            receptions =
                                emptyList()
                        )


                    val marker =
                        Marker(map)


                    marker.position =
                        GeoPoint(
                            station.latitude,
                            station.longitude
                        )


                    marker.setAnchor(
                        Marker.ANCHOR_LEFT,
                        Marker.ANCHOR_CENTER
                    )


                    marker.icon =
                        BitmapDrawable(
                            map.context.resources,
                            createAprsMarkerBitmap(
                                map.context,
                                aprsStation
                            )
                        )


                    marker.title =
                        station.callsign


                    marker.snippet =
                        buildString {

                            append(
                                "Ricevuto direttamente da "
                            )

                            append(
                                receiver.callsign
                            )


                            if (
                                station.rssi != null
                            ) {

                                append(
                                    "\nRSSI: "
                                )

                                append(
                                    station.rssi
                                )

                                append(
                                    " dBm"
                                )
                            }


                            if (
                                !station.info.isNullOrBlank()
                            ) {

                                append(
                                    "\n"
                                )

                                append(
                                    station.info
                                )
                            }
                        }


                    map.overlays.add(
                        marker
                    )
                }


                /*
                 * =================================================
                 * RICEVITORE
                 * =================================================
                 */

                val receiverAprsStation =
                    AprsStation(

                        callsign =
                            receiver.callsign,

                        latitude =
                            receiver.latitude,

                        longitude =
                            receiver.longitude,

                        symbolTable =
                            receiver.symbolTable,

                        symbolCode =
                            receiver.symbolCode,

                        info =
                            null,

                        lastSeen =
                            null,

                        receptions =
                            emptyList()
                    )


                val receiverMarker =
                    Marker(map)


                receiverMarker.position =
                    GeoPoint(
                        receiver.latitude,
                        receiver.longitude
                    )


                receiverMarker.setAnchor(
                    Marker.ANCHOR_LEFT,
                    Marker.ANCHOR_CENTER
                )


                receiverMarker.icon =
                    BitmapDrawable(
                        map.context.resources,
                        createAprsMarkerBitmap(
                            map.context,
                            receiverAprsStation
                        )
                    )


                receiverMarker.title =
                    receiver.callsign


                receiverMarker.snippet =
                    "Ricevitore"


                map.overlays.add(
                    receiverMarker
                )


                map.invalidate()
            }
        )


        /*
         * ====================================================
         * BARRA COPERTURA
         * ====================================================
         */

        Surface(

            modifier =
                Modifier
                    .align(
                        Alignment.TopCenter
                    )
                    .fillMaxWidth()
                    .padding(
                        top = 96.dp
                    ),

            tonalElevation =
                6.dp

        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 12.dp,
                            vertical = 4.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text =
                        when {

                            loading ->
                                "Copertura - caricamento..."

                            coverage != null ->
                                "Copertura - ${coverage!!.stations.size} stazioni"

                            else ->
                                "Copertura"
                        },

                    modifier =
                        Modifier.weight(
                            1f
                        )
                )


                Box {

                    Surface(
                        modifier =
                            Modifier
                                .height(
                                    34.dp
                                )
                                .clickable {
                                    expanded =
                                        true
                                },

                        shape =
                            RoundedCornerShape(
                                8.dp
                            ),

                        color =
                            ComposeColor(0xFF102635),

                        border =
                            BorderStroke(
                                1.dp,
                                ComposeColor(0xFF456475)
                            )
                    ) {

                        Row(
                            modifier =
                                Modifier.padding(
                                    horizontal = 10.dp,
                                    vertical = 4.dp
                                ),

                            verticalAlignment =
                                Alignment.CenterVertically,

                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    6.dp
                                )
                        ) {

                            Text(
                                text = "📡",
                                fontSize = 14.sp
                            )


                            Text(
                                text =
                                    selectedReceiver
                                        ?: "Ricevitore",

                                color =
                                    ComposeColor.White,

                                fontSize =
                                    12.sp,

                                fontWeight =
                                    FontWeight.Medium,

                                maxLines =
                                    1
                            )


                            Text(
                                text = "▼",

                                color =
                                    ComposeColor(0xFF90CAF9),

                                fontSize =
                                    10.sp,

                                fontWeight =
                                    FontWeight.Bold
                            )
                        }
                    }


                    DropdownMenu(

                        expanded =
                            expanded,

                        onDismissRequest = {
                            expanded =
                                false
                        }

                    ) {

                        receivers.forEach { receiver ->

                            DropdownMenuItem(

                                text = {
                                    Text(
                                        receiver
                                    )
                                },

                                onClick = {

                                    selectedReceiver =
                                        receiver

                                    expanded =
                                        false
                                }
                            )
                        }
                    }
                }
            }
        }


        /*
         * ====================================================
         * ERRORE
         * ====================================================
         */

        if (error != null) {

            Surface(

                modifier =
                    Modifier
                        .align(
                            Alignment.Center
                        ),

                tonalElevation =
                    8.dp

            ) {

                Text(
                    text =
                        "Errore: $error",

                    modifier =
                        Modifier.padding(
                            16.dp
                        )
                )
            }
        }
    }
}



