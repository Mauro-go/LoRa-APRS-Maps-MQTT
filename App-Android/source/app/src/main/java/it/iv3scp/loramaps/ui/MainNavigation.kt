package it.iv3scp.loramaps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


enum class MainSection {
    MAP,
    DASHBOARD,
    COVERAGE,
    SETTINGS
}


@Composable
fun MainNavigation(
    selected: MainSection,
    onSelected: (MainSection) -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit
) {

    var moreExpanded by remember {
        mutableStateOf(false)
    }


    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF071521)
                )
                .padding(
                    horizontal = 6.dp,
                    vertical = 4.dp
                ),

        horizontalArrangement =
            Arrangement.spacedBy(
                5.dp
            ),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        NavigationButton(
            icon = "⌖",
            label = "MAPPA",
            selected =
                selected == MainSection.MAP,
            onClick = {
                onSelected(
                    MainSection.MAP
                )
            },
            modifier =
                Modifier.weight(1f)
        )


        NavigationButton(
            icon = "▥",
            label = "DASHBOARD",
            selected =
                selected == MainSection.DASHBOARD,
            onClick = {
                onSelected(
                    MainSection.DASHBOARD
                )
            },
            modifier =
                Modifier.weight(1.45f)
        )


        NavigationButton(
            icon = "◉",
            label = "COPERTURA",
            selected =
                selected == MainSection.COVERAGE,
            onClick = {
                onSelected(
                    MainSection.COVERAGE
                )
            },
            modifier =
                Modifier.weight(1.20f)
        )


        Box(
            modifier =
                Modifier.weight(
                    0.82f
                )
        ) {

            NavigationButton(
                icon = "⋮",
                label = "MORE",
                selected = false,
                onClick = {
                    moreExpanded = true
                },
                modifier =
                    Modifier.fillMaxWidth()
            )


            DropdownMenu(
                expanded =
                    moreExpanded,

                onDismissRequest = {
                    moreExpanded = false
                },

                modifier =
                    Modifier.background(
                        Color(0xFF102635)
                    )
            ) {

                DropdownMenuItem(

                    text = {
                        Text(
                            text = "⚙  SETTINGS",
                            color = Color.White
                        )
                    },

                    onClick = {

                        moreExpanded =
                            false

                        onSettings()
                    }
                )


                DropdownMenuItem(

                    text = {
                        Text(
                            text = "✕  EXIT",
                            color = Color.White
                        )
                    },

                    onClick = {

                        moreExpanded =
                            false

                        onExit()
                    }
                )
            }
        }
    }
}


@Composable
private fun NavigationButton(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {

    val backgroundColor =
        if (selected) {
            Color(0xFF1565C0)
        } else {
            Color(0xFF102635)
        }


    val textColor =
        if (selected) {
            Color.White
        } else {
            Color(0xFFB8C9D4)
        }


    Row(
        modifier =
            modifier
                .height(
                    36.dp
                )
                .clip(
                    RoundedCornerShape(
                        8.dp
                    )
                )
                .background(
                    backgroundColor
                )
                .clickable {
                    onClick()
                }
                .padding(
                    horizontal = 6.dp,
                    vertical = 4.dp
                ),

        horizontalArrangement =
            Arrangement.Center,

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Text(
            text = icon,
            color =
                if (selected) {
                    Color(0xFF90CAF9)
                } else {
                    Color(0xFF78909C)
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )


        Text(
            text = " $label",
            color = textColor,
            fontSize = 10.sp,
            fontWeight =
                if (selected) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                },
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

