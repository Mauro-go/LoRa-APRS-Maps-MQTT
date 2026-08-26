package it.iv3scp.loramaps.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.iv3scp.loramaps.api.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL


@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onSaved: () -> Unit
) {

    val context =
        LocalContext.current

    var serverUrl by remember {
        mutableStateOf(
            ServerConfig.baseUrl
        )
    }

    var status by remember {
        mutableStateOf<String?>(null)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 110.dp,
                    bottom = 16.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                14.dp
            )
    ) {

        Text(
            text = "⚙ SETTINGS"
        )


        OutlinedTextField(
            value =
                serverUrl,

            onValueChange = {
                serverUrl =
                    it
            },

            label = {
                Text(
                    "Server LoRa APRS"
                )
            },

            placeholder = {
                Text(
                    "https://server.example/lora-api"
                )
            },

            modifier =
                Modifier.fillMaxWidth(),

            singleLine =
                true
        )


        Button(
            onClick = {

                status =
                    try {

                        runBlocking(
                            Dispatchers.IO
                        ) {

                            val url =
                                serverUrl
                                    .trim()
                                    .trimEnd('/')

                            val connection =
                                URL(
                                    "$url/status"
                                ).openConnection()
                                        as HttpURLConnection

                            try {

                                connection.requestMethod =
                                    "GET"

                                connection.connectTimeout =
                                    7000

                                connection.readTimeout =
                                    7000

                                if (
                                    connection.responseCode
                                    in 200..299
                                ) {

                                    "✓ Connessione OK"

                                } else {

                                    "✗ HTTP ${connection.responseCode}"
                                }

                            } finally {

                                connection.disconnect()
                            }
                        }

                    } catch (e: Exception) {

                        "✗ ${e.message ?: "Errore connessione"}"
                    }
            }
        ) {

            Text(
                "TEST CONNESSIONE"
            )
        }


        Button(
            onClick = {

                ServerConfig.saveBaseUrl(
                    context,
                    serverUrl
                )

                status =
                    "✓ Server salvato"

                onSaved()
            }
        ) {

            Text(
                "SALVA"
            )
        }


        Text(
            text =
                "🇮🇹 Inserire l'indirizzo delle API del proprio server LoRa APRS.`n" +
                "🇬🇧 Enter the API address of your LoRa APRS server.",

            modifier =
                Modifier.fillMaxWidth()
        )


        Text(
            text =
                "Esempio / Example: https://server.example/lora-api",

            modifier =
                Modifier.fillMaxWidth()
        )


        if (status != null) {

            Surface(
                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        status!!,

                    modifier =
                        Modifier.padding(
                            12.dp
                        )
                )
            }
        }
    }
}


