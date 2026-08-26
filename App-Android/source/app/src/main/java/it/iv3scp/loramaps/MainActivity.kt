package it.iv3scp.loramaps

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.iv3scp.loramaps.api.LivePackets
import it.iv3scp.loramaps.api.LoraApi
import it.iv3scp.loramaps.api.TrackPoint
import it.iv3scp.loramaps.api.TracksApi
import it.iv3scp.loramaps.map.TrackOverlays
import it.iv3scp.loramaps.map.animateLivePacket
import it.iv3scp.loramaps.map.clearRouteLines
import it.iv3scp.loramaps.map.clearTracks
import it.iv3scp.loramaps.map.createAprsMarkerBitmap
import it.iv3scp.loramaps.map.drawAllTracks
import it.iv3scp.loramaps.map.drawStationRoutes
import it.iv3scp.loramaps.model.AprsStation
import it.iv3scp.loramaps.ui.MainNavigation
import it.iv3scp.loramaps.ui.MainSection
import it.iv3scp.loramaps.ui.TimeSelector
import it.iv3scp.loramaps.ui.screens.CoverageScreen
import it.iv3scp.loramaps.ui.screens.DashboardScreen
import it.iv3scp.loramaps.ui.screens.SplashScreen
import it.iv3scp.loramaps.ui.screens.SettingsScreen
import it.iv3scp.loramaps.ui.theme.LoraAPRSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline


private object LiveAppState {

    @Volatile
    var foreground =
        true

    /*
     * Cambia ogni volta che l'app entra/esce
     * dal primo piano.
     *
     * Serve anche per invalidare eventuali
     * animazioni già accodate con map.post().
     */
    @Volatile
    var generation =
        0L
}


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue =
            applicationContext.packageName

        /*
         * Carica il server LoRa APRS salvato
         * nelle impostazioni dell'app.
         */
        it.iv3scp.loramaps.api.ServerConfig.init(
            applicationContext
        )

        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        hideSystemBars()

        setContent {

            LoraAPRSTheme {

                var showSplash by remember {
                    mutableStateOf(true)
                }

                LaunchedEffect(Unit) {

                    kotlinx.coroutines.delay(
                        4000
                    )

                    showSplash =
                        false
                }

                if (showSplash) {

                    SplashScreen()

                } else {

                    LoraAprsScreen()
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()

        /*
         * Nuova sessione LIVE.
         *
         * Le animazioni eventualmente rimaste
         * accodate prima dello standby diventano
         * automaticamente non valide.
         */
        LiveAppState.generation++
        LiveAppState.foreground =
            true

        hideSystemBars()
    }


    override fun onPause() {

        /*
         * Blocca immediatamente il LIVE quando
         * il telefono viene bloccato o l'app
         * passa in background.
         */
        LiveAppState.foreground =
            false

        LiveAppState.generation++

        super.onPause()
    }


    private fun hideSystemBars() {

        val controller =
            WindowCompat.getInsetsController(
                window,
                window.decorView
            )

        controller.hide(
            WindowInsetsCompat.Type.systemBars()
        )

        controller.systemBarsBehavior =
            WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}


/* ============================================================
   SCHERMATA PRINCIPALE
   ============================================================ */

@Composable
fun LoraAprsScreen() {

    val activity =
        LocalContext.current as? android.app.Activity

    var configuredServerUrl by remember {
        mutableStateOf(
            it.iv3scp.loramaps.api.ServerConfig.baseUrl
        )
    }

    var selectedHours by remember {
        mutableStateOf(1)
    }

    var selectedSection by remember {
        mutableStateOf(MainSection.MAP)
    }

    var selectedStation by remember {
        mutableStateOf<AprsStation?>(null)
    }

    var mapRef by remember {
        mutableStateOf<MapView?>(null)
    }

    var stations by remember {
        mutableStateOf<List<AprsStation>>(
            emptyList()
        )
    }

    var stationIndex by remember {
        mutableStateOf<Map<String, AprsStation>>(
            emptyMap()
        )
    }

    var tracks by remember {
        mutableStateOf<
                Map<String, List<TrackPoint>>
                >(
            emptyMap()
        )
    }

    var mapReady by remember {
        mutableStateOf(false)
    }

    var loading by remember {
        mutableStateOf(false)
    }


    val activeRouteLines =
        remember {
            mutableStateListOf<Polyline>()
        }


    val trackOverlays =
        remember {
            TrackOverlays()
        }


    /*
     * ========================================================
     * CARICAMENTO MAPPA + TRACCE
     * ========================================================
     */

    LaunchedEffect(
        selectedHours,
        configuredServerUrl
    ) {

        loading =
            true

        selectedStation =
            null


        mapRef?.let { map ->

            clearRouteLines(
                map,
                activeRouteLines
            )

            clearTracks(
                map,
                trackOverlays
            )
        }


        if (configuredServerUrl.isBlank()) {

            stations =
                emptyList()

            stationIndex =
                emptyMap()

            tracks =
                emptyMap()

            mapReady =
                false

            loading =
                false

            return@LaunchedEffect
        }


        try {

            /*
             * /map e /tracks vengono caricati
             * contemporaneamente.
             */

            val result =
                withContext(
                    Dispatchers.IO
                ) {

                    coroutineScope {

                        val stationsDeferred =
                            async {

                                LoraApi.loadStations(
                                    hours =
                                        selectedHours
                                )
                            }


                        val tracksDeferred =
                            async {

                                TracksApi.loadTracks(
                                    hours =
                                        selectedHours
                                )
                            }


                        Pair(
                            stationsDeferred.await(),
                            tracksDeferred.await()
                        )
                    }
                }


            stations =
                result.first


            stationIndex =
                result.first.associateBy {

                    it.callsign.uppercase()
                }


            tracks =
                result.second


            mapReady =
                true


        } catch (e: Exception) {

            e.printStackTrace()


        } finally {

            loading =
                false
        }
    }


    /*
     * ========================================================
     * LIVE
     * ========================================================
     */

    DisposableEffect(
        mapReady,
        mapRef,
        stationIndex
    ) {

        if (
            !mapReady ||
            mapRef == null ||
            stationIndex.isEmpty()
        ) {

            onDispose { }

        } else {

            var running =
                true


            val map =
                mapRef!!


            val liveThread =
                Thread {

                    var lastPacketId =
                        0L

                    var synchronized =
                        false

                    /*
                     * Momento dell'ultimo ciclo LIVE.
                     *
                     * Se Android sospende l'app durante
                     * standby/schermo spento, al ritorno
                     * elapsedRealtime sarà molto maggiore.
                     */
                    var lastLiveCycleTime =
                        SystemClock.elapsedRealtime()


                    while (running) {

                        /*
                         * =================================================
                         * APP IN BACKGROUND / TELEFONO BLOCCATO
                         * =================================================
                         *
                         * Non interroghiamo il LIVE e soprattutto
                         * non accodiamo animazioni.
                         *
                         * synchronized=false forza una nuova
                         * sincronizzazione appena torniamo visibili.
                         */

                        if (!LiveAppState.foreground) {

                            synchronized =
                                false

                            try {

                                Thread.sleep(
                                    500L
                                )

                            } catch (
                                e: InterruptedException
                            ) {

                                break
                            }

                            continue
                        }


                        try {

                            /*
                             * Controlliamo quanto tempo è passato
                             * dall'ultimo ciclo LIVE.
                             *
                             * Il polling normale è circa 4 secondi.
                             * Oltre 10 secondi consideriamo che
                             * l'app sia stata sospesa / telefono
                             * in standby.
                             */

                            val nowLiveCycleTime =
                                SystemClock.elapsedRealtime()

                            val livePauseMs =
                                nowLiveCycleTime -
                                lastLiveCycleTime

                            lastLiveCycleTime =
                                nowLiveCycleTime


                            val result =
                                LivePackets.fetch(
                                    lastPacketId
                                )


                            /*
                             * Prima richiesta:
                             * ci sincronizziamo soltanto.
                             */

                            if (!synchronized) {

                                lastPacketId =
                                    result.newestId

                                synchronized =
                                    true

                            } else if (livePauseMs > 10000L) {

                                /*
                                 * =================================================
                                 * RISINCRONIZZAZIONE DOPO STANDBY
                                 * =================================================
                                 *
                                 * Non animiamo i pacchetti arrivati mentre
                                 * il telefono era sospeso.
                                 *
                                 * Ci portiamo direttamente all'ultimo ID
                                 * disponibile e da qui riparte il LIVE reale.
                                 */

                                lastPacketId =
                                    result.newestId

                            } else {

                                result.packets
                                    .sortedBy {
                                        it.id
                                    }
                                    .forEach { packet ->

                                        if (!running) {

                                            return@forEach
                                        }


                                        /*
                                         * Memorizziamo la generazione
                                         * corrente.
                                         *
                                         * Se nel frattempo il telefono
                                         * viene bloccato, onPause()
                                         * incrementa generation e questo
                                         * pacchetto viene scartato anche
                                         * se era già nella coda UI.
                                         */

                                        val packetGeneration =
                                            LiveAppState.generation


                                        map.post {

                                            if (
                                                running &&
                                                LiveAppState.foreground &&
                                                LiveAppState.generation ==
                                                    packetGeneration
                                            ) {

                                                animateLivePacket(
                                                    map =
                                                        map,

                                                    packet =
                                                        packet,

                                                    stationIndex =
                                                        stationIndex
                                                )
                                            }
                                        }


                                        Thread.sleep(
                                            350L
                                        )
                                    }


                                if (
                                    result.newestId >
                                    lastPacketId
                                ) {

                                    lastPacketId =
                                        result.newestId
                                }
                            }


                        } catch (e: Exception) {

                            e.printStackTrace()
                        }


                        try {

                            Thread.sleep(
                                4000L
                            )

                        } catch (
                            e: InterruptedException
                        ) {

                            break
                        }
                    }
                }


            liveThread.start()


            onDispose {

                running =
                    false

                liveThread.interrupt()
            }
        }
    }


    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {


        /*
         * =====================================================
         * MAPPA
         * =====================================================
         */

        AndroidView(

            modifier =
                Modifier.fillMaxSize(),

            factory = { context ->

                val map =
                    MapView(context)


                mapRef =
                    map


                map.setTileSource(
                    TileSourceFactory.MAPNIK
                )


                map.setMultiTouchControls(
                    true
                )


                /*
                 * =================================================
                 * CLICK SULLA MAPPA
                 * =================================================
                 *
                 * Un tap su una zona libera chiude qualsiasi
                 * InfoWindow aperta dai punti delle tracce.
                 *
                 * Restituiamo false per non bloccare i normali
                 * comandi della mappa.
                 */

                val mapEventsOverlay =
                    MapEventsOverlay(
                        object : MapEventsReceiver {

                            override fun singleTapConfirmedHelper(
                                p: GeoPoint?
                            ): Boolean {

                                map.overlays
                                    .filterIsInstance<Marker>()
                                    .forEach { other ->

                                        if (
                                            other.isInfoWindowShown
                                        ) {
                                            other.closeInfoWindow()
                                        }
                                    }

                                map.invalidate()

                                return false
                            }


                            override fun longPressHelper(
                                p: GeoPoint?
                            ): Boolean {

                                return false
                            }
                        }
                    )


                /*
                 * Lo mettiamo all'inizio della lista:
                 * i Marker restano prioritari sul click.
                 */

                map.overlays.add(
                    0,
                    mapEventsOverlay
                )


                map.controller.setZoom(
                    9.0
                )


                map.controller.setCenter(
                    GeoPoint(
                        45.90,
                        13.65
                    )
                )


                map
            },


            update = { map ->

                /*
                 * =================================================
                 * RIMUOVI VECCHI MARKER APRS
                 * =================================================
                 *
                 * Le tracce sono Polyline, quindi non vengono
                 * eliminate qui.
                 */

                val oldAprsMarkers =
                    map.overlays
                        .filterIsInstance<Marker>()


                oldAprsMarkers.forEach {

                    map.overlays.remove(
                        it
                    )
                }


                /*
                 * =================================================
                 * TRACCE VERDI AUTOMATICHE
                 * =================================================
                 *
                 * Vengono mostrate subito.
                 * Non serve toccare la stazione.
                 */

                drawAllTracks(
                    map =
                        map,

                    tracks =
                        tracks,

                    overlays =
                        trackOverlays
                )


                /*
                 * =================================================
                 * MARKER APRS
                 * =================================================
                 */

                stations.forEach { station ->

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


                    val bitmap =
                        createAprsMarkerBitmap(
                            map.context,
                            station
                        )


                    marker.icon =
                        BitmapDrawable(
                            map.context.resources,
                            bitmap
                        )


                    /*
                     * =============================================
                     * CLICK STAZIONE
                     * =============================================
                     *
                     * Il click NON modifica la traccia verde.
                     */

                    marker.setOnMarkerClickListener {

                            _,
                            _ ->


                        /*
                         * Chiudiamo qualsiasi popup aperto
                         * sui punti della traccia.
                         */

                        map.overlays
                            .filterIsInstance<Marker>()
                            .forEach { other ->

                                if (
                                    other.isInfoWindowShown
                                ) {
                                    other.closeInfoWindow()
                                }
                            }


                        /*
                         * Togliamo soltanto il precedente
                         * percorso radio DIRECT/VIA.
                         */

                        clearRouteLines(
                            map,
                            activeRouteLines
                        )


                        /*
                         * Disegniamo il percorso radio.
                         */

                        drawStationRoutes(
                            map =
                                map,

                            station =
                                station,

                            stationIndex =
                                stationIndex,

                            activeRouteLines =
                                activeRouteLines
                        )


                        /*
                         * Apriamo il pannello.
                         */

                        selectedStation =
                            station


                        true
                    }


                    map.overlays.add(
                        marker
                    )
                }


                map.invalidate()
            }
        )


        /*
         * =====================================================
         * SERVER NON CONFIGURATO
         * =====================================================
         */

        if (
            selectedSection == MainSection.MAP &&
            configuredServerUrl.isBlank()
        ) {

            Surface(
                modifier =
                    Modifier
                        .align(
                            Alignment.Center
                        )
                        .padding(
                            24.dp
                        ),

                tonalElevation =
                    8.dp
            ) {

                Column(
                    modifier =
                        Modifier.padding(
                            18.dp
                        ),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        text =
                            "⚠ SERVER NON CONFIGURATO"
                    )


                    Text(
                        text =
                            "\n🇮🇹 Configurare l'indirizzo delle API del server LoRa APRS." +
                            "\n🇬🇧 Configure the API address of the LoRa APRS server."
                    )


                    Text(
                        text =
                            "\nMORE → SETTINGS"
                    )


                    TextButton(
                        onClick = {

                            selectedSection =
                                MainSection.SETTINGS
                        }
                    ) {

                        Text(
                            text =
                                "⚙ SETTINGS"
                        )
                    }
                }
            }
        }


        /*
         * =====================================================
         * SCHERMATE SECONDARIE
         * =====================================================
         *
         * La MapView rimane attiva sotto.
         * In questo modo tornando alla Mappa conserviamo
         * zoom, posizione, LIVE e stato della cartografia.
         */

        if (selectedSection == MainSection.DASHBOARD) {

            Surface(
                modifier = Modifier.fillMaxSize()
            ) {

                DashboardScreen(
                    stations = stations,
                    selectedHours = selectedHours
                )
            }
        }


        if (selectedSection == MainSection.COVERAGE) {

            CoverageScreen(
                stations = stations,
                selectedHours = selectedHours,
                modifier = Modifier.fillMaxSize()
            )
        }


        if (selectedSection == MainSection.SETTINGS) {

            Surface(
                modifier = Modifier.fillMaxSize()
            ) {

                SettingsScreen(
                    modifier = Modifier.fillMaxSize(),

                    onSaved = {

                        configuredServerUrl =
                            it.iv3scp.loramaps.api.ServerConfig.baseUrl

                        selectedSection =
                            MainSection.MAP
                    }
                )
            }
        }


        /*
         * =====================================================
         * BARRA SUPERIORE
         * =====================================================
         */

        Surface(

            modifier =
                Modifier
                    .align(
                        Alignment.TopCenter
                    )
                    .fillMaxWidth(),

            tonalElevation =
                6.dp

        ) {

            Row(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal =
                                10.dp,

                            vertical =
                                6.dp
                        ),

                verticalAlignment =
                    Alignment.CenterVertically

            ) {

                Text(
                    text =
                        "LoRa APRS",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium,

                    modifier =
                        Modifier.weight(
                            1f
                        )
                )


                TimeSelector(

                    selectedHours =
                        selectedHours,

                    onPeriodSelected = {

                        selectedHours =
                            it
                    }
                )
            }
        }


        /*
         * =====================================================
         * NAVIGAZIONE PRINCIPALE
         * =====================================================
         */

        Surface(

            modifier =
                Modifier
                    .align(
                        Alignment.TopCenter
                    )
                    .fillMaxWidth()
                    .padding(
                        top = 48.dp
                    ),

            tonalElevation =
                6.dp

        ) {

            MainNavigation(

                selected =
                    selectedSection,

                onSettings = {

                    selectedSection =
                        MainSection.SETTINGS

                    selectedStation =
                        null

                    mapRef?.let { map ->

                        clearRouteLines(
                            map,
                            activeRouteLines
                        )
                    }
                },

                onExit = {

                    /*
                     * Chiude completamente LoRa Maps
                     * e la rimuove dalle app recenti.
                     */

                    activity
                        ?.finishAndRemoveTask()
                },

                onSelected = {

                    selectedSection =
                        it

                    /*
                     * Chiudiamo pannello e percorso radio
                     * quando usciamo dalla mappa.
                     */

                    if (
                        it != MainSection.MAP
                    ) {

                        selectedStation =
                            null

                        mapRef?.let { map ->

                            clearRouteLines(
                                map,
                                activeRouteLines
                            )
                        }
                    }
                }
            )
        }


        /*
         * =====================================================
         * AGGIORNAMENTO
         * =====================================================
         */

        if (loading) {

            Surface(

                modifier =
                    Modifier
                        .align(
                            Alignment.TopCenter
                        )
                        .padding(
                            top =
                                58.dp
                        ),

                shape =
                    RoundedCornerShape(
                        10.dp
                    ),

                tonalElevation =
                    6.dp

            ) {

                Text(
                    text =
                        "Aggiornamento...",

                    modifier =
                        Modifier.padding(
                            horizontal =
                                14.dp,

                            vertical =
                                8.dp
                        )
                )
            }
        }


        /*
         * =====================================================
         * PANNELLO STAZIONE
         * =====================================================
         */

        selectedStation?.let { station ->

            StationPanel(

                station =
                    station,

                onClose = {

                    selectedStation =
                        null


                    mapRef?.let { map ->

                        /*
                         * CHIUDI elimina solamente
                         * DIRECT/VIA.
                         *
                         * Le tracce verdi rimangono.
                         */

                        clearRouteLines(
                            map,
                            activeRouteLines
                        )
                    }
                },

                modifier =
                    Modifier
                        .align(
                            Alignment.BottomCenter
                        )
                        .padding(
                            start =
                                12.dp,

                            end =
                                12.dp,

                            bottom =
                                12.dp
                        )
            )
        }
    }
}


/* ============================================================
   PANNELLO STAZIONE
   ============================================================ */

@Composable
fun StationPanel(
    station: AprsStation,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {

    val direct =
        station.receptions.filter {

            it.type.equals(
                "DIRECT",
                ignoreCase =
                    true
            )
        }


    val via =
        station.receptions.filter {

            it.type.equals(
                "VIA",
                ignoreCase =
                    true
            )
        }


    Card(

        modifier =
            modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                14.dp
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation =
                    8.dp
            )

    ) {

        Column(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal =
                            18.dp,

                        vertical =
                            14.dp
                    )
        ) {


            /*
             * TESTATA
             */

            Row(

                modifier =
                    Modifier.fillMaxWidth(),

                verticalAlignment =
                    Alignment.CenterVertically

            ) {

                Column(
                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Text(
                        text =
                            station.callsign,

                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall
                    )


                    if (
                        !station.info
                            .isNullOrBlank()
                    ) {

                        Text(
                            text =
                                station.info,

                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium
                        )
                    }
                }


                TextButton(
                    onClick =
                        onClose
                ) {

                    Text(
                        "CHIUDI"
                    )
                }
            }


            HorizontalDivider(
                modifier =
                    Modifier.padding(
                        vertical =
                            10.dp
                    )
            )


            /*
             * DIRECT
             */

            if (
                direct.isNotEmpty()
            ) {

                Text(
                    text =
                        "RICEZIONE DIRETTA",

                    style =
                        MaterialTheme
                            .typography
                            .labelLarge
                )


                direct.forEach { reception ->

                    Row(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical =
                                        4.dp
                                ),

                        horizontalArrangement =
                            Arrangement.SpaceBetween

                    ) {

                        Text(
                            text =
                                reception.receiver,

                            style =
                                MaterialTheme
                                    .typography
                                    .titleMedium
                        )


                        if (
                            reception.rssi != null
                        ) {

                            Text(
                                text =
                                    "${reception.rssi} dBm",

                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium
                            )
                        }
                    }
                }
            }


            /*
             * VIA
             */

            if (
                via.isNotEmpty()
            ) {

                if (
                    direct.isNotEmpty()
                ) {

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical =
                                    10.dp
                            )
                    )
                }


                Text(
                    text =
                        "RICEZIONE VIA DIGIPEATER",

                    style =
                        MaterialTheme
                            .typography
                            .labelLarge
                )


                via.forEach { reception ->

                    Column(

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical =
                                        5.dp
                                )

                    ) {

                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Text(
                                text =
                                    reception.receiver,

                                style =
                                    MaterialTheme
                                        .typography
                                        .titleMedium
                            )


                            if (
                                reception.rssi != null
                            ) {

                                Text(
                                    text =
                                        "${reception.rssi} dBm",

                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleMedium
                                )
                            }
                        }


                        if (
                            reception.via
                                .isNotEmpty()
                        ) {

                            Text(
                                text =
                                    "via " +
                                            reception.via
                                                .joinToString(
                                                    " â†’ "
                                                ),

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium
                            )
                        }
                    }
                }
            }


            /*
             * =================================================
             * VELOCITÀ
             * =================================================
             *
             * Come sulla web map:
             * viene mostrata soltanto se superiore a zero.
             */

            if (
                station.speedKmh != null &&
                station.speedKmh > 0.0
            ) {

                HorizontalDivider(
                    modifier =
                        Modifier.padding(
                            vertical =
                                10.dp
                        )
                )


                Row(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    Text(
                        text =
                            "Velocità",

                        style =
                            MaterialTheme
                                .typography
                                .labelMedium
                    )


                    Text(
                        text =
                            String.format(
                                java.util.Locale.US,
                                "%.1f km/h",
                                station.speedKmh
                            ),

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )
                }
            }


            /*
             * ULTIMO ASCOLTO
             */

            if (
                !station.lastSeen
                    .isNullOrBlank()
            ) {

                HorizontalDivider(
                    modifier =
                        Modifier.padding(
                            vertical =
                                10.dp
                        )
                )


                Text(
                    text =
                        "Ultimo ascolto",

                    style =
                        MaterialTheme
                            .typography
                            .labelMedium
                )


                Text(
                    text =
                        formatApiTimestamp(
                            station.lastSeen
                        ),

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )
            }
        }
    }
}


/* ============================================================
   FORMATTA DATA
   ============================================================ */

fun formatApiTimestamp(
    value: String?
): String {

    if (
        value.isNullOrBlank()
    ) {

        return "-"
    }


    return try {

        val clean =
            value
                .substringBefore(".")
                .substringBefore("+")


        val dateTime =
            clean.split("T")


        if (
            dateTime.size != 2
        ) {

            value

        } else {

            val date =
                dateTime[0]
                    .split("-")


            if (
                date.size != 3
            ) {

                value

            } else {

                "${date[2]}/${date[1]}/${date[0]} ${dateTime[1]}"
            }
        }


    } catch (e: Exception) {

        value
    }
}






















