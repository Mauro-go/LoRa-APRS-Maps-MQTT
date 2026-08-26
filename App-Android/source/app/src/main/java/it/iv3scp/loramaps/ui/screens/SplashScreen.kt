package it.iv3scp.loramaps.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
fun SplashScreen() {

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Color(
                        0xFF071521
                    )
                )
    ) {

        /*
         * ====================================================
         * SFONDO RADAR / RADIO
         * ====================================================
         */

        Canvas(
            modifier =
                Modifier.fillMaxSize()
        ) {

            val center =
                Offset(
                    size.width / 2f,
                    size.height / 2f
                )


            val radarColor =
                Color(
                    0xFF1E88E5
                )


            /*
             * Cerchi concentrici.
             */

            listOf(
                150f,
                280f,
                420f,
                580f
            ).forEach { radius ->

                drawCircle(
                    color =
                        radarColor.copy(
                            alpha = 0.18f
                        ),

                    radius =
                        radius,

                    center =
                        center,

                    style =
                        Stroke(
                            width = 3f
                        )
                )
            }


            /*
             * Croce radar.
             */

            drawLine(
                color =
                    radarColor.copy(
                        alpha = 0.10f
                    ),

                start =
                    Offset(
                        0f,
                        center.y
                    ),

                end =
                    Offset(
                        size.width,
                        center.y
                    ),

                strokeWidth =
                    2f
            )


            drawLine(
                color =
                    radarColor.copy(
                        alpha = 0.10f
                    ),

                start =
                    Offset(
                        center.x,
                        0f
                    ),

                end =
                    Offset(
                        center.x,
                        size.height
                    ),

                strokeWidth =
                    2f
            )


            /*
             * Alcune "tracce radio" decorative.
             */

            drawLine(
                color =
                    radarColor.copy(
                        alpha = 0.30f
                    ),

                start =
                    center,

                end =
                    Offset(
                        size.width * 0.88f,
                        size.height * 0.30f
                    ),

                strokeWidth =
                    4f,

                cap =
                    StrokeCap.Round
            )


            drawCircle(
                color =
                    Color.White.copy(
                        alpha = 0.70f
                    ),

                radius =
                    7f,

                center =
                    Offset(
                        size.width * 0.78f,
                        size.height * 0.35f
                    )
            )


            drawCircle(
                color =
                    radarColor.copy(
                        alpha = 0.85f
                    ),

                radius =
                    9f,

                center =
                    Offset(
                        size.width * 0.27f,
                        size.height * 0.68f
                    )
            )
        }


        /*
         * ====================================================
         * LOGO / TITOLO
         * ====================================================
         */

        Column(
            modifier =
                Modifier
                    .align(
                        Alignment.Center
                    )
                    .padding(
                        24.dp
                    ),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center
        ) {

            Text(
                text = "📡",
                fontSize = 82.sp
            )


            Text(
                text = "LoRa Maps",
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )


            Text(
                text = "APP",
                color = Color(0xFF64B5F6),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp
            )


            Text(
                text = "LoRa APRS Network",
                color =
                    Color.White.copy(
                        alpha = 0.70f
                    ),

                fontSize = 15.sp,

                modifier =
                    Modifier.padding(
                        top = 22.dp
                    )
            )


            Text(
                text = "IV3SCP",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,

                modifier =
                    Modifier.padding(
                        top = 8.dp
                    )
            )
        }


        /*
         * Firma inferiore.
         */

        Text(
            text = "LoRa APRS Maps MQTT",
            color =
                Color.White.copy(
                    alpha = 0.40f
                ),

            fontSize = 12.sp,

            modifier =
                Modifier
                    .align(
                        Alignment.BottomCenter
                    )
                    .padding(
                        bottom = 30.dp
                    )
        )
    }
}
