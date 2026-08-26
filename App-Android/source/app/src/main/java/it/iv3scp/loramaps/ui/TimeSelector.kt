package it.iv3scp.loramaps.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


data class TimePeriod(
    val label: String,
    val hours: Int
)


val timePeriods =
    listOf(
        TimePeriod("1h", 1),
        TimePeriod("4h", 4),
        TimePeriod("8h", 8),
        TimePeriod("24h", 24),
        TimePeriod("48h", 48),
        TimePeriod("7gg", 168)
    )


@Composable
fun TimeSelector(
    selectedHours: Int,
    onPeriodSelected: (Int) -> Unit
) {

    var expanded by remember {
        mutableStateOf(false)
    }


    val current =
        timePeriods.firstOrNull {
            it.hours == selectedHours
        } ?: timePeriods.first()


    Box {

        Surface(
            modifier =
                Modifier
                    .height(38.dp)
                    .clickable {
                        expanded = true
                    },

            shape =
                RoundedCornerShape(9.dp),

            color =
                Color(0xFF102635),

            border =
                BorderStroke(
                    1.dp,
                    Color(0xFF456475)
                )
        ) {

            Row(
                modifier =
                    Modifier.padding(
                        horizontal = 12.dp
                    ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Text(
                    text = "◷",
                    color = Color(0xFFB8C9D4),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )


                Text(
                    text = "  TEMPO",
                    color = Color(0xFFB8C9D4),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp
                )


                Text(
                    text = "  ${current.label}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )


                Text(
                    text = "  ▼",
                    color = Color(0xFF90CAF9),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }


        DropdownMenu(
            expanded = expanded,

            onDismissRequest = {
                expanded = false
            },

            modifier =
                Modifier.background(
                    Color(0xFF102635)
                )
        ) {

            timePeriods.forEach { period ->

                val selected =
                    period.hours ==
                        selectedHours


                DropdownMenuItem(

                    text = {

                        Text(
                            text =
                                period.label,

                            color =
                                if (selected) {
                                    Color.White
                                } else {
                                    Color(0xFFB8C9D4)
                                },

                            fontSize =
                                14.sp,

                            fontWeight =
                                if (selected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                        )
                    },

                    onClick = {

                        expanded =
                            false

                        onPeriodSelected(
                            period.hours
                        )
                    }
                )
            }
        }
    }
}




