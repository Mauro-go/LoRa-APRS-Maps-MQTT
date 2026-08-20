#!/usr/bin/env python3

import json
import sqlite3
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs


# ============================================================
# CONFIGURAZIONE
# ============================================================

from config import (
    API_HOST,
    API_PORT,
    DB_FILE,
    MAX_HISTORY_PACKETS,
    RECEIVERS,
)

HOST = API_HOST
PORT = API_PORT


def normalize(value):
    return str(value or "").strip().upper()


def configured_receivers():

    result = []

    for rx_id, cfg in RECEIVERS.items():

        rx_id = normalize(rx_id)
        callsign = normalize(cfg.get("callsign"))

        if not rx_id or not callsign:
            continue

        result.append(
            {
                "rx_id": rx_id,
                "callsign": callsign,
                "enabled": bool(cfg.get("enabled", True)),
            }
        )

    return result


# ============================================================
# DATABASE
# ============================================================

def get_db():
    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row
    return conn


# ============================================================
# JSON
# ============================================================

def json_response(data):
    return json.dumps(
        data,
        ensure_ascii=False,
        default=str,
    )


# ============================================================
# APRS PARSER
# ============================================================

def base91_decode(text):
    """
    Decodifica una stringa APRS Base91.
    """

    value = 0

    for char in text:
        code = ord(char)

        if code < 33 or code > 123:
            raise ValueError("Carattere Base91 non valido")

        value = value * 91 + (code - 33)

    return value


def parse_compressed_position(data):
    """
    Decodifica una posizione APRS compressa.

    Formato:

        <symbol_table><lat4><lon4><symbol_code>

    Esempio:

        L7M2RQzlSa

        L    = symbol table / overlay
        7M2R = latitude
        QzlS = longitude
        a    = symbol code
    """

    if len(data) < 10:
        return None

    symbol_table = data[0]
    lat_text = data[1:5]
    lon_text = data[5:9]
    symbol_code = data[9]

    # APRS compressed symbol table:
    # /  = primary
    # \\ = alternate
    # A-Z / a-z = overlay

    if not (
        symbol_table in ("/", "\\")
        or "A" <= symbol_table <= "Z"
        or "a" <= symbol_table <= "z"
    ):
        return None

    for char in lat_text + lon_text:
        if not (33 <= ord(char) <= 123):
            return None

    try:
        y = base91_decode(lat_text)
        x = base91_decode(lon_text)

        latitude = 90.0 - (y / 380926.0)
        longitude = -180.0 + (x / 190463.0)

    except Exception:
        return None

    if not (-90 <= latitude <= 90):
        return None

    if not (-180 <= longitude <= 180):
        return None

    return {
        "latitude": latitude,
        "longitude": longitude,
        "symbol_table": symbol_table,
        "symbol_code": symbol_code,
        "symbol": symbol_table + symbol_code,
        "position_type": "compressed",
    }


def parse_uncompressed_position(data):
    """
    Decodifica una posizione APRS non compressa.

    Formato:

        DDMM.hhN/DDDMM.hhE<symbol>

    oppure:

        DDMM.hhN\\DDDMM.hhE<symbol>

    Esempio:

        4532.94N/01203.78E_

    """

    if len(data) < 19:
        return None

    try:

        # ----------------------------------------------------
        # LATITUDINE
        # ----------------------------------------------------

        lat_text = data[0:8]

        if data[7] not in ("N", "S"):
            return None

        if data[4] != ".":
            return None

        lat_deg = int(data[0:2])
        lat_min = float(data[2:7])

        if lat_deg > 90 or lat_min >= 60:
            return None

        latitude = lat_deg + (lat_min / 60.0)

        if data[7] == "S":
            latitude = -latitude

        # ----------------------------------------------------
        # SYMBOL TABLE
        # ----------------------------------------------------

        symbol_table = data[8]

        if not (
            symbol_table in ("/", "\\")
            or "A" <= symbol_table <= "Z"
            or "a" <= symbol_table <= "z"
        ):
            return None

        # ----------------------------------------------------
        # LONGITUDINE
        # ----------------------------------------------------

        lon_text = data[9:18]

        if data[17] not in ("E", "W"):
            return None

        if data[14] != ".":
            return None

        lon_deg = int(data[9:12])
        lon_min = float(data[12:17])

        if lon_deg > 180 or lon_min >= 60:
            return None

        longitude = lon_deg + (lon_min / 60.0)

        if data[17] == "W":
            longitude = -longitude

        # ----------------------------------------------------
        # SYMBOL CODE
        # ----------------------------------------------------

        symbol_code = data[18]

        return {
            "latitude": latitude,
            "longitude": longitude,
            "symbol_table": symbol_table,
            "symbol_code": symbol_code,
            "symbol": symbol_table + symbol_code,
            "position_type": "uncompressed",
        }

    except (ValueError, IndexError):
        return None


def parse_aprs_payload(payload, depth=0):
    """
    Parser APRS della parte dati.

    Gestisce:

    - ! posizione
    - = posizione
    - / timestamp + posizione
    - @ timestamp + posizione
    - posizione compressa
    - posizione non compressa
    - packet third-party { ... }
    """

    if not payload:
        return None

    if depth > 3:
        return None

    # --------------------------------------------------------
    # THIRD-PARTY PACKET
    #
    # Esempio:
    #
    # }IR3UFS-S>APDG01,TCPIP,S55YSE-10*:;IR3UFS A *171217z...
    #
    # Cerchiamo il packet APRS incapsulato.
    # --------------------------------------------------------

    if payload.startswith("}"):

        inner = payload[1:]

        colon = inner.find(":")

        if colon >= 0:

            inner_payload = inner[colon + 1:]

            # APRS Object Report contenuto in un third-party packet.
            #
            # Esempio:
            #
            # }CALL>APRS,TCPIP,...:;OBJECT   *DDHHMMz....
            #
            # La posizione appartiene all'oggetto interno e NON alla
            # stazione LoRa esterna che ha ritrasmesso il packet.
            # Non deve quindi diventare la posizione/traccia della
            # stazione esterna.
            if inner_payload.startswith(";"):
                return None

            result = parse_aprs_payload(
                inner_payload,
                depth + 1
            )

            if result:
                return result

        # Alcuni third-party packet possono avere
        # una struttura leggermente diversa.
        # Cerchiamo direttamente i marker di posizione.

        for marker in ("!", "=", "/", "@"):

            pos = inner.find(marker)

            if pos >= 0:

                result = parse_aprs_payload(
                    inner[pos:],
                    depth + 1
                )

                if result:
                    return result

        return None

    # --------------------------------------------------------
    # POSIZIONE CON TIMESTAMP
    #
    # /DDHHMMz...
    # @DDHHMMz...
    #
    # Il timestamp occupa 7 caratteri.
    # --------------------------------------------------------

    if payload.startswith("/") or payload.startswith("@"):

        if len(payload) < 8:
            return None

        # Timestamp APRS:
        #
        # DDHHMMz
        # oppure DDHHMMh
        # oppure DDHHMMD
        #
        # Per il parsing della posizione ci interessa
        # semplicemente saltarlo.

        timestamp = payload[1:8]

        if len(timestamp) == 7:

            position_data = payload[8:]

            result = parse_compressed_position(
                position_data
            )

            if result:
                return result

            result = parse_uncompressed_position(
                position_data
            )

            if result:
                return result

    # --------------------------------------------------------
    # POSIZIONE IMMEDIATA
    #
    # ! oppure =
    # --------------------------------------------------------

    if payload.startswith("!") or payload.startswith("="):

        position_data = payload[1:]

        # Prima proviamo COMPRESSO.
        result = parse_compressed_position(
            position_data
        )

        if result:
            return result

        # Poi NON COMPRESSO.
        result = parse_uncompressed_position(
            position_data
        )

        if result:
            return result

    return None


def parse_aprs_packet(packet):
    """
    Estrae la parte dati da un packet APRS completo
    e restituisce posizione + simbolo.
    """

    result = {
        "latitude": None,
        "longitude": None,
        "symbol_table": None,
        "symbol_code": None,
        "symbol": None,
        "position_type": None,
    }

    if not packet:
        return result

    try:

        # ----------------------------------------------------
        # Header APRS:
        #
        # CALL>PATH:DATA
        # ----------------------------------------------------

        if ":" not in packet:
            return result

        payload = packet.split(":", 1)[1]

        parsed = parse_aprs_payload(payload)

        if parsed:
            result.update(parsed)

    except Exception:
        pass

    return result


# ============================================================
# API HANDLER
# ============================================================

class APIHandler(BaseHTTPRequestHandler):

    def send_json(self, data, status=200):

        body = json_response(data).encode("utf-8")

        self.send_response(status)

        self.send_header(
            "Content-Type",
            "application/json; charset=utf-8"
        )

        self.send_header(
            "Content-Length",
            str(len(body))
        )

        self.send_header(
            "Access-Control-Allow-Origin",
            "*"
        )

        self.end_headers()

        self.wfile.write(body)


    def do_GET(self):

        parsed = urlparse(self.path)

        path = parsed.path

        try:

            if path == "/api/status":
                self.api_status()

            elif path == "/api/stations":
                self.api_stations(parsed)

            elif path == "/api/packets":
                self.api_packets(parsed)

            elif path == "/api/stats":
                self.api_stats()

            else:

                self.send_json(
                    {
                        "error": "Endpoint non trovato"
                    },
                    404
                )

        except Exception as e:

            self.send_json(
                {
                    "error": str(e)
                },
                500
            )


    # ========================================================
    # STATUS
    # ========================================================

    def api_status(self):

        conn = get_db()
        result = []

        for receiver in configured_receivers():

            rx_id = receiver["rx_id"]
            callsign = receiver["callsign"]

            row = conn.execute(
                """
                SELECT
                    COUNT(*) AS packets,
                    COUNT(DISTINCT callsign) AS stations,
                    MAX(timestamp) AS last_packet
                FROM packets
                WHERE receiver = ?
                """,
                (rx_id,)
            ).fetchone()

            position = {
                "latitude": None,
                "longitude": None,
                "symbol_table": None,
                "symbol_code": None,
                "symbol": None,
                "position_type": None,
            }

            historical_rows = conn.execute(
                """
                SELECT packet
                FROM packets
                WHERE UPPER(callsign) = UPPER(?)
                ORDER BY id DESC
                LIMIT 500
                """,
                (callsign,)
            ).fetchall()

            for historical_row in historical_rows:

                parsed_position = parse_aprs_packet(
                    historical_row["packet"]
                )

                if (
                    parsed_position.get("latitude") is not None
                    and parsed_position.get("longitude") is not None
                ):
                    position.update(
                        {
                            "latitude": parsed_position.get("latitude"),
                            "longitude": parsed_position.get("longitude"),
                            "symbol_table": parsed_position.get("symbol_table"),
                            "symbol_code": parsed_position.get("symbol_code"),
                            "symbol": parsed_position.get("symbol"),
                            "position_type": parsed_position.get("position_type"),
                        }
                    )
                    break

            result.append(
                {
                    "callsign": callsign,
                    "receiver_id": rx_id,

                    # Backward compatibility with the current frontend.
                    # This is now RX1/RX2/... and no longer a configurable
                    # MQTT topic name.
                    "mqtt_name": rx_id,

                    "enabled": receiver["enabled"],
                    "packets": row["packets"],
                    "stations": row["stations"],
                    "last_packet": row["last_packet"],
                    **position,
                }
            )

        conn.close()

        self.send_json(
            {
                "receivers": result
            }
        )


    # ========================================================
    # STATIONS
    # ========================================================

    def api_stations(self, parsed):

        params = parse_qs(parsed.query)

        receiver = params.get(
            "receiver",
            [None]
        )[0]

        limit = int(
            params.get(
                "limit",
                [100]
            )[0]
        )

        limit = min(
            max(limit, 1),
            500
        )

        conn = get_db()

        if receiver:

            rows = conn.execute(
                """
                SELECT
                    callsign,
                    COUNT(*) AS packets,
                    MAX(timestamp) AS last_seen
                FROM packets
                WHERE receiver_callsign = ?
                GROUP BY callsign
                ORDER BY last_seen DESC
                LIMIT ?
                """,
                (receiver, limit)
            ).fetchall()

        else:

            rows = conn.execute(
                """
                SELECT
                    callsign,
                    COUNT(*) AS packets,
                    MAX(timestamp) AS last_seen
                FROM packets
                GROUP BY callsign
                ORDER BY last_seen DESC
                LIMIT ?
                """,
                (limit,)
            ).fetchall()

        conn.close()

        self.send_json(
            {
                "stations": [
                    dict(row)
                    for row in rows
                ]
            }
        )


    # ========================================================
    # PACKETS
    # ========================================================

    def api_packets(self, parsed):

        params = parse_qs(parsed.query)
        conn = get_db()

        hours_value = params.get(
            "hours",
            [None]
        )[0]

        if hours_value is not None:

            try:
                hours = float(hours_value)
            except (TypeError, ValueError):
                hours = 1.0

            hours = min(
                max(hours, 0.01),
                24 * 31
            )

            minimum_time = (
                datetime.now(timezone.utc)
                - timedelta(hours=hours)
            ).isoformat()

            rows = conn.execute(
                """
                SELECT
                    id,
                    timestamp,
                    receiver,
                    receiver_callsign,
                    callsign,
                    topic,
                    packet
                FROM packets
                WHERE timestamp >= ?
                ORDER BY id DESC
                LIMIT ?
                """,
                (
                    minimum_time,
                    int(MAX_HISTORY_PACKETS),
                )
            ).fetchall()

        else:

            try:
                limit = int(
                    params.get(
                        "limit",
                        [50]
                    )[0]
                )
            except (TypeError, ValueError):
                limit = 50

            limit = min(
                max(limit, 1),
                500
            )

            rows = conn.execute(
                """
                SELECT
                    id,
                    timestamp,
                    receiver,
                    receiver_callsign,
                    callsign,
                    topic,
                    packet
                FROM packets
                ORDER BY id DESC
                LIMIT ?
                """,
                (limit,)
            ).fetchall()

        last_known_cache = {}

        def get_last_known(callsign):

            key = normalize(callsign)

            if key in last_known_cache:
                return last_known_cache[key]

            result = {
                "last_known_latitude": None,
                "last_known_longitude": None,
                "last_known_symbol_table": None,
                "last_known_symbol_code": None,
                "last_known_symbol": None,
                "last_known_position_type": None,
            }

            historical_rows = conn.execute(
                """
                SELECT packet
                FROM packets
                WHERE UPPER(callsign) = UPPER(?)
                ORDER BY id DESC
                LIMIT 1000
                """,
                (callsign,)
            )

            for historical_row in historical_rows:

                parsed_historical = parse_aprs_packet(
                    historical_row["packet"]
                )

                if (
                    parsed_historical.get("latitude") is not None
                    and
                    parsed_historical.get("longitude") is not None
                ):

                    result["last_known_latitude"] = (
                        parsed_historical.get("latitude")
                    )
                    result["last_known_longitude"] = (
                        parsed_historical.get("longitude")
                    )
                    result["last_known_symbol_table"] = (
                        parsed_historical.get("symbol_table")
                    )
                    result["last_known_symbol_code"] = (
                        parsed_historical.get("symbol_code")
                    )
                    result["last_known_symbol"] = (
                        parsed_historical.get("symbol")
                    )
                    result["last_known_position_type"] = (
                        parsed_historical.get("position_type")
                    )
                    break

            last_known_cache[key] = result
            return result

        packets = []

        for row in rows:

            item = dict(row)

            parsed_aprs = parse_aprs_packet(
                item.get("packet", "")
            )

            item.update(parsed_aprs)

            callsign = item.get("callsign")

            if callsign:
                item.update(
                    get_last_known(callsign)
                )

            packets.append(item)

        conn.close()

        self.send_json(
            {
                "packets": packets
            }
        )


    # ========================================================
    # STATISTICHE
    # ========================================================

    def api_stats(self):

        conn = get_db()

        total = conn.execute(
            """
            SELECT COUNT(*) AS count
            FROM packets
            """
        ).fetchone()["count"]

        stations = conn.execute(
            """
            SELECT COUNT(DISTINCT callsign) AS count
            FROM packets
            """
        ).fetchone()["count"]

        receivers = conn.execute(
            """
            SELECT COUNT(DISTINCT receiver_callsign) AS count
            FROM packets
            WHERE receiver_callsign IS NOT NULL
            """
        ).fetchone()["count"]

        now = datetime.now(timezone.utc)

        yesterday = (
            now - timedelta(days=1)
        ).isoformat()

        last24 = conn.execute(
            """
            SELECT COUNT(*) AS count
            FROM packets
            WHERE timestamp >= ?
            """,
            (yesterday,)
        ).fetchone()["count"]

        last_packet = conn.execute(
            """
            SELECT
                timestamp,
                receiver_callsign,
                callsign,
                packet
            FROM packets
            ORDER BY id DESC
            LIMIT 1
            """
        ).fetchone()

        conn.close()

        self.send_json(
            {
                "total_packets": total,
                "unique_stations": stations,
                "receivers_with_data": receivers,
                "packets_24h": last24,
                "last_packet":
                    dict(last_packet)
                    if last_packet
                    else None,
            }
        )


    # ========================================================
    # LOG
    # ========================================================

    def log_message(self, format, *args):

        pass


# ============================================================
# AVVIO
# ============================================================

if __name__ == "__main__":

    server = HTTPServer(
        (HOST, PORT),
        APIHandler
    )

    print(
        f"LoRa APRS API in ascolto su "
        f"http://{HOST}:{PORT}",
        flush=True
    )

    try:

        server.serve_forever()

    except KeyboardInterrupt:

        pass

    finally:

        server.server_close()
