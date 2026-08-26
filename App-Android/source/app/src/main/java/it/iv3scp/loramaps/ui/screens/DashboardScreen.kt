package it.iv3scp.loramaps.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.iv3scp.loramaps.api.DashboardApi
import it.iv3scp.loramaps.api.DashboardPacket
import it.iv3scp.loramaps.api.DashboardReceiver
import it.iv3scp.loramaps.api.DashboardStation
import it.iv3scp.loramaps.api.DashboardStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext


@Composable
fun DashboardScreen(
    stations: List<it.iv3scp.loramaps.model.AprsStation>,
    selectedHours: Int,
    modifier: Modifier = Modifier
) {

    var stats by remember {
        mutableStateOf<DashboardStats?>(null)
    }

    var receivers by remember {
        mutableStateOf<List<DashboardReceiver>>(
            emptyList()
        )
    }

    var receiverStations by remember {
        mutableStateOf<
            Map<String, List<DashboardStation>>
        >(
            emptyMap()
        )
    }

    var latestPackets by remember {
        mutableStateOf<List<DashboardPacket>>(
            emptyList()
        )
    }

    var loading by remember {
        mutableStateOf(true)
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }


    LaunchedEffect(Unit) {

        loading = true
        error = null

        try {

            val result =
                withContext(Dispatchers.IO) {

                    coroutineScope {

                        val statsDeferred =
                            async {
                                DashboardApi.loadStats()
                            }

                        val receiversDeferred =
                            async {
                                DashboardApi.loadReceivers()
                            }

                        val packetsDeferred =
                            async {
                                DashboardApi.loadLatestPackets(50)
                            }

                        val loadedStats =
                            statsDeferred.await()

                        val loadedReceivers =
                            receiversDeferred.await()

                        val stationMap =
                            mutableMapOf<
                                String,
                                List<DashboardStation>
                            >()

                        loadedReceivers.forEach { receiver ->

                            stationMap[receiver.callsign] =
                                DashboardApi.loadStations(
                                    receiver.callsign
                                )
                        }

                        Triple(
                            loadedStats,
                            Pair(
                                loadedReceivers,
                                stationMap
                            ),
                            packetsDeferred.await()
                        )
                    }
                }

            stats = result.first
            receivers = result.second.first
            receiverStations = result.second.second
            latestPackets = result.third

        } catch (e: kotlinx.coroutines.CancellationException) {

            throw e

        } catch (e: Exception) {

            error =
                e.message
                    ?: "Errore caricamento dashboard"

        } finally {

            loading = false
        }
    }


    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 100.dp,
                    bottom = 20.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "Dashboard",
            style =
                MaterialTheme
                    .typography
                    .headlineMedium
        )


        if (loading) {

            Text(
                text = "Aggiornamento..."
            )
        }


        if (error != null) {

            Surface {

                Text(
                    text =
                        "Errore: $error",

                    modifier =
                        Modifier.padding(12.dp)
                )
            }
        }


        stats?.let { s ->

            Card(
                modifier =
                    Modifier.fillMaxWidth(),

                elevation =
                    CardDefaults.cardElevation(
                        defaultElevation = 4.dp
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(16.dp)
                ) {

                    Text(
                        text = "Riepilogo",
                        style =
                            MaterialTheme
                                .typography
                                .titleLarge
                    )

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 10.dp
                            )
                    )

                    DashboardValue(
                        "Pacchetti totali",
                        s.totalPackets.toString()
                    )

                    DashboardValue(
                        "Stazioni uniche",
                        s.uniqueStations.toString()
                    )

                    DashboardValue(
                        "Pacchetti 24h",
                        s.packets24h.toString()
                    )

                    DashboardValue(
                        "Ricevitori attivi",
                        receivers
                            .count {
                                it.enabled &&
                                it.packets > 0
                            }
                            .toString()
                    )
                }
            }
        }


        receivers.forEach { receiver ->

            Card(
                modifier =
                    Modifier.fillMaxWidth(),

                elevation =
                    CardDefaults.cardElevation(
                        defaultElevation = 4.dp
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(16.dp)
                ) {

                    Text(
                        text =
                            "📡 ${receiver.callsign}",

                        style =
                            MaterialTheme
                                .typography
                                .titleLarge
                    )

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 10.dp
                            )
                    )

                    DashboardValue(
                        "Pacchetti",
                        receiver.packets.toString()
                    )

                    DashboardValue(
                        "Stazioni",
                        receiver.stations.toString()
                    )

                    DashboardValue(
                        "Ultimo pacchetto",
                        formatDashboardTime(
                            receiver.lastPacket
                        )
                    )
                }
            }
        }


        Text(
            text =
                "📻 Stazioni ricevute per ricevitore",

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        receivers.forEach { receiver ->

            val list =
                receiverStations[
                    receiver.callsign
                ] ?: emptyList()


            Card(
                modifier =
                    Modifier.fillMaxWidth(),

                elevation =
                    CardDefaults.cardElevation(
                        defaultElevation = 3.dp
                    )
            ) {

                Column(
                    modifier =
                        Modifier.padding(14.dp)
                ) {

                    Text(
                        text =
                            receiver.callsign,

                        style =
                            MaterialTheme
                                .typography
                                .titleMedium
                    )

                    Text(
                        text =
                            "${list.size} stazioni"
                    )

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 8.dp
                            )
                    )


                    list.forEach { station ->

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        vertical = 4.dp
                                    ),

                            horizontalArrangement =
                                Arrangement.SpaceBetween
                        ) {

                            Column(
                                modifier =
                                    Modifier.weight(1f)
                            ) {

                                Text(
                                    text =
                                        station.callsign
                                )

                                Text(
                                    text =
                                        formatDashboardTime(
                                            station.lastSeen
                                        ),

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall
                                )
                            }


                            Text(
                                text =
                                    station.packets
                                        .toString()
                            )
                        }
                    }
                }
            }
        }


        Text(
            text =
                "📦 Ultimi pacchetti",

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        Card(
            modifier =
                Modifier.fillMaxWidth(),

            elevation =
                CardDefaults.cardElevation(
                    defaultElevation = 3.dp
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(14.dp)
            ) {

                latestPackets.forEach { packet ->

                    Text(
                        text =
                            "${packet.callsign} → ${packet.receiver}",

                        style =
                            MaterialTheme
                                .typography
                                .titleSmall
                    )

                    Text(
                        text =
                            formatDashboardTime(
                                packet.timestamp
                            ),

                        style =
                            MaterialTheme
                                .typography
                                .bodySmall
                    )

                    if (
                        !packet.packet.isNullOrBlank()
                    ) {

                        Text(
                            text =
                                packet.packet,

                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )
                    }

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 7.dp
                            )
                    )
                }
            }
        }
    }
}


@Composable
private fun DashboardValue(
    label: String,
    value: String
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 4.dp
                ),

        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {

        Text(label)

        Text(
            text = value,
            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )
    }
}


private fun formatDashboardTime(
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

        val parts =
            clean.split("T")

        if (parts.size != 2) {

            value

        } else {

            val date =
                parts[0].split("-")

            if (date.size != 3) {

                value

            } else {

                "${date[2]}/${date[1]}/${date[0]} ${parts[1]}"
            }
        }

    } catch (e: Exception) {

        value
    }
}
