package it.iv3scp.loramaps.api

data class CoverageReceiver(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val symbolTable: String,
    val symbolCode: String
)

data class CoverageStation(
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val symbolTable: String,
    val symbolCode: String,
    val info: String?,
    val rssi: Int?,
    val timestamp: String?
)

data class CoverageData(
    val receiver: CoverageReceiver,
    val hours: Double,
    val stationCount: Int,
    val stations: List<CoverageStation>
)
