package it.iv3scp.loramaps.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import it.iv3scp.loramaps.R
import it.iv3scp.loramaps.model.AprsStation

fun createAprsMarkerBitmap(
    context: Context,
    station: AprsStation
): Bitmap {

    val alternate =
        station.symbolTable != "/"

    val spriteResource =
        if (alternate) {
            R.drawable.aprs_symbols_24_1
        } else {
            R.drawable.aprs_symbols_24_0
        }

    val sprite =
        BitmapFactory.decodeResource(
            context.resources,
            spriteResource
        )

    val code =
        if (station.symbolCode.isNotEmpty()) {
            station.symbolCode[0].code
        } else {
            '>'.code
        }

    val index =
        (code - 33)
            .coerceIn(0, 93)

    val columns = 16

    val col =
        index % columns

    val row =
        index / columns

    val cellWidth =
        sprite.width / columns

    val cellHeight =
        sprite.height / 6

    val symbolBitmap =
        Bitmap.createBitmap(
            sprite,
            col * cellWidth,
            row * cellHeight,
            cellWidth,
            cellHeight
        )

    /*
     * Dimensione simbolo APRS approvata.
     */

    val symbolSize = 72

    val scaledSymbol =
        Bitmap.createScaledBitmap(
            symbolBitmap,
            symbolSize,
            symbolSize,
            false
        )

    /*
     * Nominativo.
     */

    val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    textPaint.color =
        Color.BLACK

    textPaint.textSize =
        40f

    textPaint.typeface =
        Typeface.create(
            Typeface.DEFAULT,
            Typeface.BOLD
        )

    val textWidth =
        textPaint
            .measureText(station.callsign)
            .toInt()

    val padding = 10

    val width =
        symbolSize +
        padding +
        textWidth +
        12

    val height = 78

    val result =
        Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

    val canvas =
        Canvas(result)

    /*
     * Simbolo APRS.
     */

    canvas.drawBitmap(
        scaledSymbol,
        0f,
        ((height - symbolSize) / 2).toFloat(),
        null
    )

    /*
     * Overlay APRS bianco.
     */

    if (
        station.symbolTable.length == 1 &&
        station.symbolTable[0] != '/' &&
        station.symbolTable[0] != '\\'
    ) {

        val overlayPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        overlayPaint.color =
            Color.WHITE

        overlayPaint.textSize =
            36f

        overlayPaint.typeface =
            Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

        overlayPaint.textAlign =
            Paint.Align.CENTER

        canvas.drawText(
            station.symbolTable,
            symbolSize / 2f,
            height / 2f + 12f,
            overlayPaint
        )
    }

    /*
     * Nominativo.
     */

    canvas.drawText(
        station.callsign,
        (symbolSize + padding).toFloat(),
        52f,
        textPaint
    )

    return result
}
