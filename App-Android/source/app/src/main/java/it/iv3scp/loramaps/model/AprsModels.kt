package it.iv3scp.loramaps.model

data class Reception(
    val receiver: String,
    val type: String,
    val via: List<String>,
    val rssi: Int?
)

data class AprsStation(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val symbolTable: String,
    val symbolCode: String,
    val info: String?,
    val lastSeen: String?,
    val speedKmh: Double? = null,
    val receptions: List<Reception>
)

