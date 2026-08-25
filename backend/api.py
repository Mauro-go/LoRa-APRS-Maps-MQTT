#!/usr/bin/env python3

import json
import re
import sqlite3
import time
import importlib.util

from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs


# ============================================================
# CONFIGURAZIONE
# ============================================================

from config import (
    API_HOST,
    API_PORT,
    DB_FILE,
    RECEIVERS,
)

HOST = API_HOST
PORT = API_PORT

V1_REFERENCE = "/opt/lora-aprs/backend/api-v1-reference.py"


# ============================================================
# CARICA PARSER APRS V1
# ============================================================

spec = importlib.util.spec_from_file_location(
    "api_v1_reference",
    V1_REFERENCE,
)

api_v1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api_v1)

parse_aprs_packet = api_v1.parse_aprs_packet


# ============================================================
# PATH APRS
# ============================================================

GENERIC_PATH = re.compile(
    r"^(WIDE\d*(?:-\d+)?|TRACE\d*(?:-\d+)?|RELAY|"
    r"RPN\d*(?:-\d+)?|RFONLY|NOGATE|GATE)$",
    re.I,
)

CALLSIGN = re.compile(
    r"^[A-Z0-9]{1,6}(?:-[0-9]{1,2})?$",
    re.I,
)


def parse_outer_header(packet):

    if not packet or ":" not in packet:
        return None

    header, payload = packet.split(":", 1)

    if ">" not in header:
        return None

    source, rest = header.split(">", 1)

    parts = [
        x.strip()
        for x in rest.split(",")
        if x.strip()
    ]

    if not parts:
        return None

    return {
        "source": source.strip(),
        "destination": parts[0],
        "path": parts[1:],
        "payload": payload,
    }


def analyse_radio_path(packet, receiver_callsign):

    parsed = parse_outer_header(packet)

    if not parsed:
        return {
            "type": "UNKNOWN",
            "via": [],
            "radio_path": [],
        }

    source = parsed["source"]

    digipeaters = []
    special = False

    for raw in parsed["path"]:

        raw = raw.strip()

        used = raw.endswith("*")

        token = raw[:-1] if used else raw
        token = token.strip()

        upper = token.upper()

        # TCPIP/TCPXX nel FRAME ESTERNO:
        # non lo consideriamo copertura RF normale.
        if upper in ("TCPIP", "TCPXX"):
            special = True
            continue

        # WIDE, RFONLY ecc. non sono stazioni reali.
        if GENERIC_PATH.match(token):
            continue

        # Solo nominativi marcati * sono digi realmente usati.
        if used and CALLSIGN.match(token):

            if token not in digipeaters:
                digipeaters.append(token)

    radio_path = [source]

    for digi in digipeaters:

        if digi not in radio_path:
            radio_path.append(digi)

    if receiver_callsign:

        # Se il ricevitore coincide con la sorgente ma il pacchetto
        # è tornato attraverso un digipeater, il ricevitore deve
        # comparire nuovamente alla fine del percorso:
        #
        # IV3SCP-10 -> S57PNX-8 -> IV3SCP-10
        #
        if (
            receiver_callsign == source
            and digipeaters
        ):
            radio_path.append(receiver_callsign)

        elif receiver_callsign not in radio_path:
            radio_path.append(receiver_callsign)

    if special:
        reception_type = "SPECIAL"

    elif digipeaters:
        reception_type = "VIA"

    else:
        reception_type = "DIRECT"

    return {
        "type": reception_type,
        "via": digipeaters,
        "radio_path": radio_path,
    }



# ============================================================
# RICEVITORI CONFIGURATI
# ============================================================

def get_configured_receivers():

    result = []

    for key, cfg in RECEIVERS.items():

        if not isinstance(cfg, dict):
            continue

        callsign = str(
            cfg.get("callsign") or key or ""
        ).strip()

        if not callsign:
            continue

        result.append({
            "id":
                str(key),

            "callsign":
                callsign,

            "enabled":
                cfg.get("enabled", True) is not False,
        })

    return result



# ============================================================
# INFORMAZIONI / VELOCITA APRS
# ============================================================

def extract_aprs_comment(packet):

    if not packet or ":" not in packet:
        return None

    payload = packet.split(":", 1)[1]

    if payload.startswith("}"):
        return None

    if (
        payload.startswith(":")
        or payload.startswith("T#")
        or payload.startswith(";")
        or payload.startswith(")")
    ):
        return None

    comment = None


    # ========================================================
    # STATUS APRS
    # ========================================================

    if payload.startswith(">"):

        comment = payload[1:].strip()


    # ========================================================
    # POSIZIONE CON TIMESTAMP @ oppure /
    #
    # APRS:
    #
    #   @221055zL7LrQQw6T>]2ALORA RF.Guru - Q5
    #
    #   @        data type
    #   221055z  timestamp APRS (7 caratteri)
    #   L7LrQQw6T> posizione compressa (10 caratteri)
    #   ]2A      c/s/T
    #   LORA...  commento
    #
    # Supportiamo sia posizione compressa sia non compressa.
    # ========================================================

    elif (
        payload.startswith(("@", "/"))
        and len(payload) >= 8
    ):

        timed_body = payload[8:]

        # ----------------------------------------------------
        # TIMESTAMP + POSIZIONE NON COMPRESSA
        # ----------------------------------------------------

        timed_uncompressed = re.match(
            r'^'
            r'\d{4}\.\d{2}[NS][/\\]'
            r'\d{5}\.\d{2}[EW].'
            r'(.*)$',
            timed_body
        )

        if timed_uncompressed:

            comment = timed_uncompressed.group(1)

        # ----------------------------------------------------
        # TIMESTAMP + POSIZIONE COMPRESSA
        #
        # table + lat4 + lon4 + symbol + c/s/T = 13 caratteri
        # ----------------------------------------------------

        elif len(timed_body) >= 13:

            table = timed_body[0]

            if (
                table in ("/", "\\")
                or "A" <= table <= "Z"
                or "a" <= table <= "z"
            ):
                comment = timed_body[13:]


    # ========================================================
    # POSIZIONE NON COMPRESSA
    #
    # DTI + 19 caratteri posizione/simbolo.
    # ========================================================

    elif (
        payload.startswith(("!", "="))
        and len(payload) >= 20
        and len(payload) > 8
        and payload[8] in ("N", "S")
    ):

        comment = payload[20:]


    # ========================================================
    # POSIZIONE COMPRESSA
    #
    # DTI + table + lat4 + lon4 + symbol + c/s/T
    # ========================================================

    elif (
        payload.startswith(("!", "="))
        and len(payload) >= 14
    ):

        comment = payload[14:]


    if comment is None:
        return None

    comment = str(comment).strip()

    if not comment:
        return None


    # --------------------------------------------------------
    # Marcatori LoRa APRS iniziali.
    # --------------------------------------------------------

    comment = comment.lstrip(" !").strip()

    comment = re.sub(
        r'^[! ]*G',
        "",
        comment,
        count=1
    ).strip()


    # --------------------------------------------------------
    # WX compatto iniziale.
    #
    # 000/000g000t077r000b10120h71v+01
    #
    # Se dopo la rimozione non rimane testo, il packet
    # non contiene informazioni stazione.
    # --------------------------------------------------------

    comment = re.sub(
        r'^'
        r'\d{3}/\d{3}'
        r'(?:g\d{3})?'
        r'(?:t-?\d{3})?'
        r'(?:r\d{3})?'
        r'(?:p\d{3})?'
        r'(?:P\d{3})?'
        r'(?:h\d{2,3})?'
        r'(?:b\d{5})?'
        r'(?:v[+-]?\d+)?'
        r'\s*',
        "",
        comment,
        count=1,
        flags=re.I
    ).strip()


    # --------------------------------------------------------
    # Campi tecnici/meteo iniziali singoli.
    # --------------------------------------------------------

    patterns = [
        r'^\.\.\./\.\.\.g\.\.\.',
        r'^c\d{3}',
        r'^s\d{3}',
        r'^g\d{3}',
        r'^t-?\d{3}',
        r'^h\d{2,3}',
        r'^b\d{5}',
        r'^r\d{3}',
        r'^p\d{3}',
        r'^P\d{3}',
        r'^v[+-]?\d+',
    ]

    changed = True

    while changed and comment:

        changed = False

        for pattern in patterns:

            cleaned = re.sub(
                pattern,
                "",
                comment,
                count=1,
                flags=re.I
            ).strip()

            if cleaned != comment:
                comment = cleaned
                changed = True


    # --------------------------------------------------------
    # Altitudine APRS finale /A=xxxxxx
    # --------------------------------------------------------

    comment = re.sub(
        r'/A=\d{6}\s*$',
        "",
        comment,
        flags=re.I
    ).strip()


    # --------------------------------------------------------
    # Telemetria Base91 finale |...|
    # --------------------------------------------------------

    comment = re.sub(
        r'\|[^|]*\|\s*$',
        "",
        comment
    ).strip()


    if not comment:
        return None


    # Solo dati numerici/tecnici.
    if re.fullmatch(
        r'[-+./_0-9A-Fa-f\s]+',
        comment
    ):
        return None


    # Frammenti compressi brevissimi.
    if (
        len(comment) <= 3
        and
        " " not in comment
    ):
        return None


    return comment


def extract_aprs_speed_kmh(packet):
    """
    Estrae la velocita' APRS di movimento in km/h.

    Gestisce:
      - posizione non compressa con ccc/sss
      - posizione compressa con c/s/T

    Nel formato compresso cs NON rappresenta sempre
    course/speed. In particolare con sorgente NMEA GGA
    puo' rappresentare l'altitudine.
    """

    if not packet or ":" not in packet:
        return None

    payload = packet.split(":", 1)[1]

    # Third-party: non attribuire il dato
    # alla stazione esterna.
    if payload.startswith("}"):
        return None


    # ========================================================
    # POSIZIONE NON COMPRESSA
    #
    # DDMM.mmN/DDDMM.mmE>ccc/sss
    #
    # sss = velocita' in nodi
    # ========================================================

    m = re.match(
        r'^[!=@/]?'
        r'\d{4}\.\d{2}[NS][/\\]'
        r'\d{5}\.\d{2}[EW].'
        r'(\d{3})/(\d{3})',
        payload
    )

    if m:

        knots = int(
            m.group(2)
        )

        if 0 <= knots <= 998:

            return round(
                knots * 1.852,
                1
            )


    # ========================================================
    # POSIZIONE COMPRESSA
    #
    # payload:
    #
    #   ! oppure =
    #   symbol table
    #   latitude      4
    #   longitude     4
    #   symbol code
    #   c
    #   s
    #   T
    #
    # ========================================================

    if (
        payload.startswith(("!", "="))
        and len(payload) >= 14
    ):

        body = payload[1:]

        if len(body) < 13:
            return None

        c_char = body[10]
        s_char = body[11]
        t_char = body[12]

        c = ord(c_char) - 33
        speed_code = ord(s_char) - 33
        t = ord(t_char) - 33


        # T usa 6 bit:
        #
        # bit 5   = GPS fix
        # bit 4-3 = NMEA source
        # bit 2-0 = compression origin
        #
        # NMEA:
        #   00 other
        #   01 GLL
        #   10 GGA
        #   11 RMC

        if not (0 <= t <= 63):
            return None

        nmea_source = (
            t >> 3
        ) & 0x03


        # GGA:
        # cs puo' rappresentare ALTITUDINE,
        # quindi non e' course/speed.
        if nmea_source == 2:
            return None


        # c = 90, carattere "{":
        # pre-calculated radio range.
        if c == 90:
            return None


        # c 0..89:
        # course = c * 4
        #
        # s:
        # speed knots = 1.08^s - 1

        if (
            0 <= c <= 89
            and
            0 <= speed_code <= 89
        ):

            knots = (
                1.08 ** speed_code
            ) - 1

            return round(
                knots * 1.852,
                1
            )

    return None


# ============================================================
# DATABASE
# ============================================================

def get_db():

    conn = sqlite3.connect(DB_FILE)
    conn.row_factory = sqlite3.Row

    return conn


# ============================================================
# FALLBACK POSIZIONE / SIMBOLO
# ============================================================

def find_last_valid_position(
    conn,
    callsign,
    before_id=None,
):

    if before_id is None:

        rows = conn.execute("""
            SELECT
                id,
                timestamp,
                packet
            FROM packets
            WHERE callsign = ?
            ORDER BY id DESC
        """, (callsign,))

    else:

        rows = conn.execute("""
            SELECT
                id,
                timestamp,
                packet
            FROM packets
            WHERE callsign = ?
              AND id < ?
            ORDER BY id DESC
        """, (callsign, before_id))

    for row in rows:

        parsed = parse_aprs_packet(
            row["packet"]
        )

        if (
            parsed.get("latitude") is not None
            and
            parsed.get("longitude") is not None
        ):

            return {
                "latitude":
                    parsed.get("latitude"),

                "longitude":
                    parsed.get("longitude"),

                "symbol_table":
                    parsed.get("symbol_table"),

                "symbol_code":
                    parsed.get("symbol_code"),

                "symbol":
                    parsed.get("symbol"),

                "position_type":
                    parsed.get("position_type"),

                "position_timestamp":
                    row["timestamp"],

                "position_packet_id":
                    row["id"],
            }

    return None


# ============================================================
# COSTRUZIONE MAPPA
# ============================================================

def build_map(hours):

    total_start = time.perf_counter()

    cutoff = (
        datetime.now(timezone.utc)
        - timedelta(hours=hours)
    ).isoformat()

    conn = get_db()

    # --------------------------------------------------------
    # ULTIMO PACCHETTO DI OGNI STAZIONE NEL PERIODO
    # --------------------------------------------------------

    t0 = time.perf_counter()

    station_rows = conn.execute("""
        SELECT p.*
        FROM packets p
        JOIN (
            SELECT
                callsign,
                MAX(id) AS last_id
            FROM packets
            WHERE timestamp >= ?
              AND callsign IS NOT NULL
              AND callsign <> ''
            GROUP BY callsign
        ) latest
          ON p.id = latest.last_id
        ORDER BY p.timestamp DESC
    """, (cutoff,)).fetchall()

    query_station_ms = (
        time.perf_counter() - t0
    ) * 1000


    # --------------------------------------------------------
    # ULTIMA RICEZIONE PER STAZIONE / RICEVITORE
    # --------------------------------------------------------

    t0 = time.perf_counter()

    reception_rows = conn.execute("""
        SELECT p.*
        FROM packets p
        JOIN (
            SELECT
                callsign,
                receiver_callsign,
                MAX(id) AS last_id
            FROM packets
            WHERE timestamp >= ?
              AND callsign IS NOT NULL
              AND callsign <> ''
              AND receiver_callsign IS NOT NULL
              AND receiver_callsign <> ''
            GROUP BY
                callsign,
                receiver_callsign
        ) latest
          ON p.id = latest.last_id
        ORDER BY p.timestamp DESC
    """, (cutoff,)).fetchall()

    query_reception_ms = (
        time.perf_counter() - t0
    ) * 1000


    # --------------------------------------------------------
    # COSTRUISCI RICEZIONI
    # --------------------------------------------------------

    t0 = time.perf_counter()

    receptions_by_station = {}

    special_count = 0

    for row in reception_rows:

        path = analyse_radio_path(
            row["packet"],
            row["receiver_callsign"],
        )

        if path["type"] == "SPECIAL":
            special_count += 1

        reception = {
            "receiver":
                row["receiver_callsign"],

            "type":
                path["type"],

            "via":
                path["via"],

            "radio_path":
                path["radio_path"],

            "timestamp":
                row["timestamp"],

            "rssi":
                row["rssi"],

            "packet_id":
                row["id"],
        }

        receptions_by_station.setdefault(
            row["callsign"],
            []
        ).append(reception)

    path_ms = (
        time.perf_counter() - t0
    ) * 1000


    # --------------------------------------------------------
    # STAZIONI + FALLBACK POSIZIONE
    # --------------------------------------------------------

    t0 = time.perf_counter()

    stations = []

    fallback_used = 0
    no_position = 0

    for row in station_rows:

        aprs = parse_aprs_packet(
            row["packet"]
        )

        latitude = aprs.get("latitude")
        longitude = aprs.get("longitude")

        position_timestamp = row["timestamp"]
        position_packet_id = row["id"]

        position_source = "LATEST"

        symbol_table = aprs.get(
            "symbol_table"
        )

        symbol_code = aprs.get(
            "symbol_code"
        )

        symbol = aprs.get(
            "symbol"
        )

        position_type = aprs.get(
            "position_type"
        )

        # ----------------------------------------------------
        # FALLBACK STORICO
        # ----------------------------------------------------

        if (
            latitude is None
            or longitude is None
        ):

            fallback = find_last_valid_position(
                conn,
                row["callsign"],
                row["id"],
            )

            if fallback:

                fallback_used += 1

                latitude = fallback[
                    "latitude"
                ]

                longitude = fallback[
                    "longitude"
                ]

                symbol_table = fallback[
                    "symbol_table"
                ]

                symbol_code = fallback[
                    "symbol_code"
                ]

                symbol = fallback[
                    "symbol"
                ]

                position_type = fallback[
                    "position_type"
                ]

                position_timestamp = fallback[
                    "position_timestamp"
                ]

                position_packet_id = fallback[
                    "position_packet_id"
                ]

                position_source = "HISTORICAL"

            else:

                no_position += 1
                position_source = "NONE"

        # ----------------------------------------------------
        # Informazioni stazione.
        #
        # Preferiamo un commento valido di posizione.
        # Solo se non esiste usiamo un packet STATUS ">".
        # ----------------------------------------------------

        info_rows = conn.execute("""
            SELECT
                id,
                packet
            FROM packets
            WHERE callsign = ?
              AND id <= ?
            ORDER BY id DESC
            LIMIT 500
        """, (
            row["callsign"],
            row["id"],
        )).fetchall()

        station_info = None


        # Prima scelta: packet di posizione.
        for info_row in info_rows:

            raw = str(
                info_row["packet"] or ""
            )

            if ":" not in raw:
                continue

            payload = raw.split(":", 1)[1]

            if not payload.startswith(("!", "=", "/", "@")):
                continue

            candidate = extract_aprs_comment(raw)

            if candidate:

                station_info = candidate
                break


        # Seconda scelta: STATUS APRS.
        if not station_info:

            for info_row in info_rows:

                raw = str(
                    info_row["packet"] or ""
                )

                if ":" not in raw:
                    continue

                payload = raw.split(":", 1)[1]

                if not payload.startswith(">"):
                    continue

                candidate = extract_aprs_comment(raw)

                if candidate:

                    station_info = candidate
                    break


        stations.append({

            "callsign":
                row["callsign"],

            "latitude":
                latitude,

            "longitude":
                longitude,

            "symbol_table":
                symbol_table,

            "symbol_code":
                symbol_code,

            "symbol":
                symbol,

            "position_type":
                position_type,

            "position_source":
                position_source,

            "position_timestamp":
                position_timestamp,

            "position_packet_id":
                position_packet_id,

            "last_seen":
                row["timestamp"],

            "last_packet_id":
                row["id"],

            "last_receiver":
                row["receiver_callsign"],

            "rssi":
                row["rssi"],

            "info":
                station_info,

            "speed_kmh":
                extract_aprs_speed_kmh(
                    row["packet"]
                ),

            "receptions":
                receptions_by_station.get(
                    row["callsign"],
                    []
                ),
        })

    parse_fallback_ms = (
        time.perf_counter() - t0
    ) * 1000

    conn.close()

    total_ms = (
        time.perf_counter() - total_start
    ) * 1000

    return {

        "hours":
            hours,

        "generated_at":
            datetime.now(
                timezone.utc
            ).isoformat(),

        "stats": {

            "stations":
                len(stations),

            "receptions":
                len(reception_rows),

            "fallback_positions":
                fallback_used,

            "stations_without_position":
                no_position,

            "special_receptions":
                special_count,

            "query_station_ms":
                round(
                    query_station_ms,
                    2
                ),

            "query_reception_ms":
                round(
                    query_reception_ms,
                    2
                ),

            "path_ms":
                round(
                    path_ms,
                    2
                ),

            "parse_fallback_ms":
                round(
                    parse_fallback_ms,
                    2
                ),

            "total_ms":
                round(
                    total_ms,
                    2
                ),
        },

        "stations":
            stations,
    }


# ============================================================
# HTTP
# ============================================================

class Handler(BaseHTTPRequestHandler):

    def do_GET(self):

        parsed = urlparse(
            self.path
        )

        if parsed.path == "/":

            return self.send_json({
                "service":
                    "LoRa APRS V2 API",

                "version":
                    "2.0-lab",

                "database":
                    DB_FILE,

                "endpoint":
                    "/api/map?hours=24",
            })

        if parsed.path == "/api/tracks":

            query = parse_qs(parsed.query)

            try:
                hours = float(
                    query.get("hours", ["24"])[0]
                )
            except Exception:
                hours = 24

            if hours <= 0:
                hours = 24

            cutoff = (
                datetime.now(timezone.utc)
                - timedelta(hours=hours)
            ).isoformat()

            start = time.perf_counter()

            conn = get_db()

            rows = conn.execute("""
                SELECT
                    id,
                    timestamp,
                    callsign,
                    receiver_callsign,
                    packet
                FROM packets
                WHERE timestamp >= ?
                  AND callsign IS NOT NULL
                  AND callsign <> ''
                ORDER BY callsign ASC, id ASC
            """, (cutoff,)).fetchall()

            tracks = {}

            previous_position = {}

            packets_with_position = 0
            duplicate_positions = 0

            for row in rows:

                aprs = parse_aprs_packet(
                    row["packet"]
                )

                lat = aprs.get("latitude")
                lon = aprs.get("longitude")

                if (
                    lat is None
                    or lon is None
                ):
                    continue

                packets_with_position += 1

                callsign = row["callsign"]

                previous = previous_position.get(
                    callsign
                )

                if (
                    previous is not None
                    and
                    abs(lat - previous[0]) < 0.000001
                    and
                    abs(lon - previous[1]) < 0.000001
                ):
                    duplicate_positions += 1
                    continue

                path = analyse_radio_path(
                    row["packet"],
                    row["receiver_callsign"],
                )

                point = {
                    "packet_id":
                        row["id"],

                    "timestamp":
                        row["timestamp"],

                    "latitude":
                        lat,

                    "longitude":
                        lon,

                    "receiver":
                        row["receiver_callsign"],

                    "type":
                        path["type"],

                    "via":
                        path["via"],

                    "radio_path":
                        path["radio_path"],

                    "speed_kmh":
                        extract_aprs_speed_kmh(
                            row["packet"]
                        ),
                }

                tracks.setdefault(
                    callsign,
                    []
                ).append(point)

                previous_position[callsign] = (
                    lat,
                    lon
                )

            conn.close()


            # Teniamo solo stazioni con almeno
            # due posizioni distinte.
            tracks = {
                callsign: points
                for callsign, points
                in tracks.items()
                if len(points) >= 2
            }


            total_points = sum(
                len(points)
                for points in tracks.values()
            )

            total_ms = (
                time.perf_counter() - start
            ) * 1000


            return self.send_json({

                "hours":
                    hours,

                "generated_at":
                    datetime.now(
                        timezone.utc
                    ).isoformat(),

                "stats": {

                    "packets":
                        len(rows),

                    "packets_with_position":
                        packets_with_position,

                    "duplicate_positions_removed":
                        duplicate_positions,

                    "stations_with_tracks":
                        len(tracks),

                    "points":
                        total_points,

                    "total_ms":
                        round(
                            total_ms,
                            2
                        ),
                },

                "tracks":
                    tracks,
            })

        if parsed.path == "/api/track":

            query = parse_qs(parsed.query)

            callsign = (
                query.get("callsign", [""])[0]
                .strip()
                .upper()
            )

            try:
                hours = float(
                    query.get("hours", ["24"])[0]
                )
            except Exception:
                hours = 24

            if hours <= 0:
                hours = 24

            if not callsign:

                return self.send_json({
                    "error": "callsign required",
                    "points": [],
                })

            cutoff = (
                datetime.now(timezone.utc)
                - timedelta(hours=hours)
            ).isoformat()

            conn = get_db()

            rows = conn.execute("""
                SELECT
                    id,
                    timestamp,
                    callsign,
                    receiver_callsign,
                    packet
                FROM packets
                WHERE callsign = ?
                  AND timestamp >= ?
                ORDER BY id ASC
            """, (
                callsign,
                cutoff,
            )).fetchall()

            points = []

            previous_lat = None
            previous_lon = None

            packets_with_position = 0
            duplicate_positions = 0

            for row in rows:

                aprs = parse_aprs_packet(
                    row["packet"]
                )

                lat = aprs.get("latitude")
                lon = aprs.get("longitude")

                if (
                    lat is None
                    or lon is None
                ):
                    continue

                packets_with_position += 1

                # Evita punti consecutivi identici.
                #
                # Manteniamo comunque il primo punto
                # della posizione.
                if (
                    previous_lat is not None
                    and previous_lon is not None
                    and abs(lat - previous_lat) < 0.000001
                    and abs(lon - previous_lon) < 0.000001
                ):

                    duplicate_positions += 1
                    continue

                path = analyse_radio_path(
                    row["packet"],
                    row["receiver_callsign"],
                )

                points.append({

                    "packet_id":
                        row["id"],

                    "timestamp":
                        row["timestamp"],

                    "latitude":
                        lat,

                    "longitude":
                        lon,

                    "receiver":
                        row["receiver_callsign"],

                    "type":
                        path["type"],

                    "via":
                        path["via"],

                    "radio_path":
                        path["radio_path"],

                    "symbol_table":
                        aprs.get("symbol_table"),

                    "symbol_code":
                        aprs.get("symbol_code"),
                })

                previous_lat = lat
                previous_lon = lon

            conn.close()

            return self.send_json({

                "callsign":
                    callsign,

                "hours":
                    hours,

                "stats": {

                    "packets":
                        len(rows),

                    "packets_with_position":
                        packets_with_position,

                    "duplicate_positions_removed":
                        duplicate_positions,

                    "points":
                        len(points),
                },

                "points":
                    points,
            })

        if parsed.path == "/api/live":

            query = parse_qs(parsed.query)

            try:
                after_id = int(
                    query.get("after_id", ["0"])[0]
                )
            except Exception:
                after_id = 0

            conn = get_db()

            # Prima chiamata del browser:
            # sincronizziamo solamente l'ultimo ID corrente,
            # senza riprodurre lo storico come traffico live.
            if after_id <= 0:

                row = conn.execute("""
                    SELECT COALESCE(MAX(id), 0) AS newest_id
                    FROM packets
                """).fetchone()

                newest_id = int(row["newest_id"])

                conn.close()

                return self.send_json({
                    "after_id": 0,
                    "newest_id": newest_id,
                    "packets": [],
                })

            rows = conn.execute("""
                SELECT
                    id,
                    timestamp,
                    callsign,
                    receiver_callsign,
                    packet
                FROM packets
                WHERE id > ?
                ORDER BY id ASC
                LIMIT 100
            """, (after_id,)).fetchall()

            packets = []

            for row in rows:

                aprs = parse_aprs_packet(
                    row["packet"]
                )

                path = analyse_radio_path(
                    row["packet"],
                    row["receiver_callsign"],
                )

                packets.append({
                    "id": row["id"],
                    "timestamp": row["timestamp"],
                    "callsign": row["callsign"],
                    "receiver": row["receiver_callsign"],
                    "type": path["type"],
                    "via": path["via"],
                    "radio_path": path["radio_path"],
                    "latitude": aprs.get("latitude"),
                    "longitude": aprs.get("longitude"),
                    "symbol_table": aprs.get("symbol_table"),
                    "symbol_code": aprs.get("symbol_code"),
                })

            if rows:
                newest_id = rows[-1]["id"]
            else:
                newest_id = after_id

            conn.close()

            return self.send_json({
                "after_id": after_id,
                "newest_id": newest_id,
                "packets": packets,
            })

        # ====================================================
        # DASHBOARD V2 - STATISTICHE GENERALI
        # ====================================================

        if parsed.path == "/api/stats":

            conn = get_db()

            total_packets = conn.execute("""
                SELECT COUNT(*) AS n
                FROM packets
            """).fetchone()["n"]

            unique_stations = conn.execute("""
                SELECT COUNT(DISTINCT callsign) AS n
                FROM packets
                WHERE callsign IS NOT NULL
                  AND callsign <> ''
            """).fetchone()["n"]

            cutoff_24h = (
                datetime.now(timezone.utc)
                - timedelta(hours=24)
            ).isoformat()

            packets_24h = conn.execute("""
                SELECT COUNT(*) AS n
                FROM packets
                WHERE timestamp >= ?
            """, (cutoff_24h,)).fetchone()["n"]

            conn.close()

            return self.send_json({
                "total_packets":
                    total_packets,

                "unique_stations":
                    unique_stations,

                "packets_24h":
                    packets_24h,
            })


        # ====================================================
        # DASHBOARD V2 - STATO RICEVITORI
        # ====================================================

        if parsed.path == "/api/status":

            conn = get_db()

            result = []

            for receiver in get_configured_receivers():

                if not receiver["enabled"]:
                    continue

                callsign = receiver["callsign"]

                row = conn.execute("""
                    SELECT
                        COUNT(*) AS packets,
                        COUNT(DISTINCT callsign) AS stations,
                        MAX(timestamp) AS last_packet
                    FROM packets
                    WHERE receiver_callsign = ?
                """, (callsign,)).fetchone()

                pos = find_last_valid_position(
                    conn,
                    callsign,
                )

                item = {
                    "id":
                        receiver["id"],

                    "callsign":
                        callsign,

                    "enabled":
                        True,

                    "packets":
                        int(row["packets"] or 0),

                    "stations":
                        int(row["stations"] or 0),

                    "last_packet":
                        row["last_packet"],

                    "latitude":
                        None,

                    "longitude":
                        None,
                }

                if pos:

                    item["latitude"] = (
                        pos["latitude"]
                    )

                    item["longitude"] = (
                        pos["longitude"]
                    )

                result.append(item)

            conn.close()

            return self.send_json({
                "receivers":
                    result,
            })


        # ====================================================
        # DASHBOARD V2 - STAZIONI PER RICEVITORE
        # ====================================================

        if parsed.path == "/api/stations":

            query = parse_qs(parsed.query)

            receiver = str(
                query.get(
                    "receiver",
                    [""]
                )[0]
            ).strip()

            try:

                limit = int(
                    query.get(
                        "limit",
                        ["500"]
                    )[0]
                )

            except Exception:

                limit = 500

            limit = min(
                max(limit, 1),
                500
            )

            if not receiver:

                return self.send_json({
                    "stations":
                        [],
                })

            conn = get_db()

            rows = conn.execute("""
                SELECT
                    callsign,
                    COUNT(*) AS packets,
                    MAX(timestamp) AS last_seen
                FROM packets
                WHERE receiver_callsign = ?
                  AND callsign IS NOT NULL
                  AND callsign <> ''
                GROUP BY UPPER(callsign)
                ORDER BY last_seen DESC
                LIMIT ?
            """, (
                receiver,
                limit,
            )).fetchall()

            stations = [
                {
                    "callsign":
                        row["callsign"],

                    "packets":
                        int(row["packets"] or 0),

                    "last_seen":
                        row["last_seen"],
                }
                for row in rows
            ]

            conn.close()

            return self.send_json({
                "receiver":
                    receiver,

                "stations":
                    stations,
            })


        # ====================================================
        # DASHBOARD V2 - ULTIMI PACCHETTI
        # ====================================================

        if parsed.path == "/api/packets":

            query = parse_qs(parsed.query)

            try:

                limit = int(
                    query.get(
                        "limit",
                        ["50"]
                    )[0]
                )

            except Exception:

                limit = 50

            limit = min(
                max(limit, 1),
                500
            )

            conn = get_db()

            rows = conn.execute("""
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
            """, (limit,)).fetchall()

            packets = [
                {
                    "id":
                        row["id"],

                    "timestamp":
                        row["timestamp"],

                    "received_at":
                        row["timestamp"],

                    "receiver":
                        row["receiver"],

                    "receiver_callsign":
                        row["receiver_callsign"],

                    "callsign":
                        row["callsign"],

                    "topic":
                        row["topic"],

                    "packet":
                        row["packet"],
                }
                for row in rows
            ]

            conn.close()

            return self.send_json({
                "packets":
                    packets,
            })


        if parsed.path == "/api/receivers":

            conn = get_db()

            result = []

            for receiver in get_configured_receivers():

                if not receiver["enabled"]:
                    continue

                callsign = receiver["callsign"]

                pos = find_last_valid_position(
                    conn,
                    callsign,
                )

                item = {
                    "id":
                        receiver["id"],

                    "callsign":
                        callsign,

                    "enabled":
                        True,

                    "latitude":
                        None,

                    "longitude":
                        None,

                    "symbol_table":
                        None,

                    "symbol_code":
                        None,
                }

                if pos:

                    item["latitude"] = (
                        pos["latitude"]
                    )

                    item["longitude"] = (
                        pos["longitude"]
                    )

                    item["symbol_table"] = (
                        pos["symbol_table"]
                    )

                    item["symbol_code"] = (
                        pos["symbol_code"]
                    )

                result.append(item)

            conn.close()

            return self.send_json({
                "receivers":
                    result,
            })


        if parsed.path == "/api/coverage":

            query = parse_qs(parsed.query)

            requested_receiver = str(
                query.get("receiver", [""])[0]
            ).strip()

            try:

                hours = float(
                    query.get(
                        "hours",
                        ["1"]
                    )[0]
                )

            except Exception:

                hours = 1

            if hours <= 0:
                hours = 1


            # ----------------------------------------------------
            # RICEVITORI DA CONFIG
            # ----------------------------------------------------

            configured = [
                x
                for x in get_configured_receivers()
                if x["enabled"]
            ]

            if not configured:

                return self.send_json({
                    "error":
                        "No receiver configured",

                    "packets":
                        [],
                })


            selected = None

            if requested_receiver:

                requested_upper = (
                    requested_receiver.upper()
                )

                for item in configured:

                    if (
                        item["callsign"].upper()
                        == requested_upper
                    ):

                        selected = item
                        break


            if selected is None:

                selected = configured[0]


            receiver = selected["callsign"]


            cutoff = (
                datetime.now(timezone.utc)
                - timedelta(hours=hours)
            ).isoformat()


            start = time.perf_counter()

            conn = get_db()


            # ----------------------------------------------------
            # POSIZIONE DEL RICEVITORE
            # ----------------------------------------------------

            receiver_position = (
                find_last_valid_position(
                    conn,
                    receiver,
                )
            )


            # ----------------------------------------------------
            # PACCHETTI ASCOLTATI DA QUESTO RICEVITORE
            # NEL PERIODO RICHIESTO
            # ----------------------------------------------------

            rows = conn.execute("""
                SELECT
                    id,
                    timestamp,
                    receiver,
                    receiver_callsign,
                    callsign,
                    topic,
                    rssi,
                    packet
                FROM packets
                WHERE receiver_callsign = ?
                  AND timestamp >= ?
                ORDER BY id DESC
            """, (
                receiver,
                cutoff,
            )).fetchall()


            #
            # Un solo record per stazione.
            #
            # IMPORTANTE:
            # deve essere una ricezione DIRECT secondo
            # il FRAME APRS ESTERNO.
            #
            latest_direct = {}


            for row in rows:

                source = str(
                    row["callsign"] or ""
                ).strip()

                if not source:
                    continue

                path = analyse_radio_path(
                    row["packet"],
                    row["receiver_callsign"],
                )

                if path["type"] != "DIRECT":
                    continue

                key = source.upper()

                #
                # rows è DESC:
                # il primo DIRECT è il più recente.
                #
                if key not in latest_direct:

                    latest_direct[key] = row


            packets = []

            fallback_positions = 0


            for key, row in latest_direct.items():

                raw_packet = str(
                    row["packet"] or ""
                )

                aprs = parse_aprs_packet(
                    raw_packet
                )

                lat = aprs.get(
                    "latitude"
                )

                lon = aprs.get(
                    "longitude"
                )

                symbol_table = aprs.get(
                    "symbol_table"
                )

                symbol_code = aprs.get(
                    "symbol_code"
                )

                symbol = aprs.get(
                    "symbol"
                )

                position_type = aprs.get(
                    "position_type"
                )


                #
                # Se l'ultimo ascolto DIRECT non contiene
                # una posizione, recuperiamo l'ultima
                # posizione APRS valida della stazione.
                #
                if (
                    lat is None
                    or lon is None
                ):

                    fallback = (
                        find_last_valid_position(
                            conn,
                            row["callsign"],
                        )
                    )

                    if fallback:

                        lat = fallback[
                            "latitude"
                        ]

                        lon = fallback[
                            "longitude"
                        ]

                        symbol_table = (
                            symbol_table
                            or fallback[
                                "symbol_table"
                            ]
                        )

                        symbol_code = (
                            symbol_code
                            or fallback[
                                "symbol_code"
                            ]
                        )

                        symbol = (
                            symbol
                            or fallback[
                                "symbol"
                            ]
                        )

                        position_type = (
                            position_type
                            or fallback[
                                "position_type"
                            ]
                        )

                        fallback_positions += 1


                if (
                    lat is None
                    or lon is None
                ):
                    continue


                # ----------------------------------------------------
                # INFORMAZIONI STAZIONE
                #
                # Il periodo della Coverage stabilisce se la
                # stazione compare.
                #
                # La descrizione viene invece recuperata
                # dall'ultima informazione APRS valida conosciuta,
                # anche fuori dal periodo selezionato.
                # ----------------------------------------------------

                info_rows = conn.execute("""
                    SELECT
                        packet
                    FROM packets
                    WHERE callsign = ?
                    ORDER BY id DESC
                    LIMIT 500
                """, (
                    row["callsign"],
                )).fetchall()

                station_info = None


                # Prima scelta: commento di posizione.
                for info_row in info_rows:

                    info_raw = str(
                        info_row["packet"] or ""
                    )

                    if ":" not in info_raw:
                        continue

                    info_payload = info_raw.split(
                        ":",
                        1
                    )[1]

                    if not info_payload.startswith(
                        ("!", "=", "/", "@")
                    ):
                        continue

                    candidate = extract_aprs_comment(
                        info_raw
                    )

                    if candidate:
                        station_info = candidate
                        break


                # Seconda scelta: STATUS APRS.
                if not station_info:

                    for info_row in info_rows:

                        info_raw = str(
                            info_row["packet"] or ""
                        )

                        if ":" not in info_raw:
                            continue

                        info_payload = info_raw.split(
                            ":",
                            1
                        )[1]

                        if not info_payload.startswith(">"):
                            continue

                        candidate = extract_aprs_comment(
                            info_raw
                        )

                        if candidate:
                            station_info = candidate
                            break


                station_speed_kmh = (
                    extract_aprs_speed_kmh(
                        raw_packet
                    )
                )


                #
                # Formato volutamente compatibile con
                # la vecchia coverage.html.
                #
                packets.append({

                    "id":
                        row["id"],

                    "timestamp":
                        row["timestamp"],

                    "received_at":
                        row["timestamp"],

                    "receiver":
                        row["receiver"],

                    "receiver_callsign":
                        row["receiver_callsign"],

                    "callsign":
                        row["callsign"],

                    "topic":
                        row["topic"],

                    "rssi":
                        row["rssi"],

                    "packet":
                        raw_packet,

                    "latitude":
                        lat,

                    "longitude":
                        lon,

                    "symbol_table":
                        symbol_table,

                    "symbol_code":
                        symbol_code,

                    "symbol":
                        symbol,

                    "position_type":
                        position_type,

                    "info":
                        station_info,

                    "speed_kmh":
                        station_speed_kmh,

                    "reception_type":
                        "DIRECT",

                    "via":
                        [],

                    "radio_path": [
                        row["callsign"],
                        receiver,
                    ],
                })


            conn.close()


            receiver_data = {

                "id":
                    selected["id"],

                "callsign":
                    receiver,

                "enabled":
                    True,

                "latitude":
                    None,

                "longitude":
                    None,

                "symbol_table":
                    None,

                "symbol_code":
                    None,
            }


            if receiver_position:

                receiver_data[
                    "latitude"
                ] = receiver_position[
                    "latitude"
                ]

                receiver_data[
                    "longitude"
                ] = receiver_position[
                    "longitude"
                ]

                receiver_data[
                    "symbol_table"
                ] = receiver_position[
                    "symbol_table"
                ]

                receiver_data[
                    "symbol_code"
                ] = receiver_position[
                    "symbol_code"
                ]


            total_ms = (
                time.perf_counter()
                - start
            ) * 1000


            return self.send_json({

                "receiver":
                    receiver_data,

                "hours":
                    hours,

                "stations":
                    len(packets),

                "packets":
                    packets,

                "stats": {

                    "database_packets":
                        len(rows),

                    "direct_stations":
                        len(packets),

                    "fallback_positions":
                        fallback_positions,

                    "total_ms":
                        round(
                            total_ms,
                            2
                        ),
                },
            })


        if parsed.path == "/api/station":

            query = parse_qs(parsed.query)

            callsign = (
                query.get("callsign", [""])[0]
                .strip()
                .upper()
            )

            try:
                hours = float(
                    query.get("hours", ["24"])[0]
                )
            except Exception:
                hours = 24

            if hours <= 0:
                hours = 24

            if not callsign:
                return self.send_json({
                    "error": "callsign required",
                })

            cutoff = (
                datetime.now(timezone.utc)
                - timedelta(hours=hours)
            ).isoformat()

            total_start = time.perf_counter()

            conn = get_db()

            # ====================================================
            # PACKET DEL PERIODO
            # ====================================================

            t0 = time.perf_counter()

            rows = conn.execute("""
                SELECT
                    id,
                    timestamp,
                    receiver,
                    receiver_callsign,
                    callsign,
                    topic,
                    rssi,
                    packet
                FROM packets
                WHERE timestamp >= ?
                ORDER BY id DESC
            """, (cutoff,)).fetchall()

            query_ms = (
                time.perf_counter() - t0
            ) * 1000


            # ====================================================
            # CONTATORI
            # ====================================================

            transmitted_count = 0
            received_count = 0
            transit_count = 0

            special_count = 0

            first_activity = None
            last_activity = None


            # ====================================================
            # GRUPPI
            # ====================================================

            transmitted_groups = {}
            received_groups = {}
            transit_groups = {}


            # ====================================================
            # DATI STAZIONE
            # ====================================================

            latest_packet = None

            latest_position = None

            latest_symbol = {
                "symbol_table": None,
                "symbol_code": None,
                "symbol": None,
                "position_type": None,
            }

            #
            # Manteniamo solo pochi packet TX recenti.
            #
            # Servono al frontend per:
            #
            # - meteo APRS
            # - informazioni/commento stazione
            # - eventuali dettagli tecnici
            #
            source_packets = []

            MAX_SOURCE_PACKETS = 50


            # ====================================================
            # FUNZIONI GRUPPI
            # ====================================================

            def update_group(
                groups,
                key,
                name,
                category,
                via,
                timestamp,
                rssi=None,
            ):

                if key not in groups:

                    groups[key] = {
                        "name":
                            name,

                        "category":
                            category,

                        "via":
                            via,

                        "count":
                            0,

                        "last":
                            None,

                        "last_rssi":
                            None,

                        "best_rssi":
                            None,
                    }

                item = groups[key]

                item["count"] += 1

                if (
                    timestamp
                    and (
                        item["last"] is None
                        or timestamp > item["last"]
                    )
                ):
                    item["last"] = timestamp
                    item["last_rssi"] = rssi

                if rssi is not None:
                    try:
                        rssi_value = int(rssi)

                        if (
                            item["best_rssi"] is None
                            or rssi_value > item["best_rssi"]
                        ):
                            item["best_rssi"] = rssi_value

                    except (TypeError, ValueError):
                        pass


            # ====================================================
            # ANALISI
            # ====================================================

            t0 = time.perf_counter()

            for row in rows:

                source = str(
                    row["callsign"] or ""
                ).strip()

                source_upper = source.upper()

                receiver = str(
                    row["receiver_callsign"] or ""
                ).strip()

                receiver_upper = receiver.upper()

                raw_packet = str(
                    row["packet"] or ""
                )

                path = analyse_radio_path(
                    raw_packet,
                    row["receiver_callsign"],
                )

                reception_type = path["type"]

                via_list = [
                    str(x).strip()
                    for x in path["via"]
                    if str(x).strip()
                ]

                via_upper = [
                    x.upper()
                    for x in via_list
                ]

                via_text = (
                    " → ".join(via_list)
                    if via_list
                    else None
                )

                timestamp = row["timestamp"]

                rssi = row["rssi"]


                # ------------------------------------------------
                # TX DELLA STAZIONE
                # ------------------------------------------------

                if source_upper == callsign:

                    transmitted_count += 1

                    if latest_packet is None:

                        latest_packet = {
                            "id":
                                row["id"],

                            "timestamp":
                                timestamp,

                            "receiver_callsign":
                                receiver,

                            "callsign":
                                source,

                            "packet":
                                raw_packet,
                        }


                    #
                    # Packet recenti necessari per
                    # meteo/commento.
                    #
                    if (
                        len(source_packets)
                        < MAX_SOURCE_PACKETS
                    ):

                        aprs_source = parse_aprs_packet(
                            raw_packet
                        )

                        source_packets.append({
                            "id":
                                row["id"],

                            "timestamp":
                                timestamp,

                            "received_at":
                                timestamp,

                            "receiver_callsign":
                                receiver,

                            "callsign":
                                source,

                            "packet":
                                raw_packet,

                            "rssi":
                                rssi,

                            "latitude":
                                aprs_source.get(
                                    "latitude"
                                ),

                            "longitude":
                                aprs_source.get(
                                    "longitude"
                                ),

                            "symbol_table":
                                aprs_source.get(
                                    "symbol_table"
                                ),

                            "symbol_code":
                                aprs_source.get(
                                    "symbol_code"
                                ),

                            "symbol":
                                aprs_source.get(
                                    "symbol"
                                ),

                            "position_type":
                                aprs_source.get(
                                    "position_type"
                                ),
                        })


                    #
                    # Prima posizione valida TX,
                    # dato che rows è DESC.
                    #
                    if latest_position is None:

                        aprs = parse_aprs_packet(
                            raw_packet
                        )

                        lat = aprs.get(
                            "latitude"
                        )

                        lon = aprs.get(
                            "longitude"
                        )

                        if (
                            lat is not None
                            and lon is not None
                        ):

                            latest_position = {
                                "latitude":
                                    lat,

                                "longitude":
                                    lon,

                                "timestamp":
                                    timestamp,

                                "packet_id":
                                    row["id"],
                            }

                            latest_symbol = {
                                "symbol_table":
                                    aprs.get(
                                        "symbol_table"
                                    ),

                                "symbol_code":
                                    aprs.get(
                                        "symbol_code"
                                    ),

                                "symbol":
                                    aprs.get(
                                        "symbol"
                                    ),

                                "position_type":
                                    aprs.get(
                                        "position_type"
                                    ),
                            }


                    #
                    # SPECIAL equivale ai casi TCPIP/TCPXX
                    # esterni: li contiamo ma non li
                    # classifichiamo come copertura RF.
                    #
                    if reception_type == "SPECIAL":

                        special_count += 1

                    elif receiver:

                        if reception_type == "VIA":

                            key = (
                                receiver_upper +
                                "|VIA|" +
                                (
                                    via_text.upper()
                                    if via_text
                                    else "UNKNOWN"
                                )
                            )

                            update_group(
                                transmitted_groups,
                                key,
                                receiver,
                                "via",
                                via_text or "UNKNOWN",
                                timestamp,
                                rssi,
                            )

                        else:

                            key = (
                                receiver_upper +
                                "|DIRECT"
                            )

                            update_group(
                                transmitted_groups,
                                key,
                                receiver,
                                "direct",
                                None,
                                timestamp,
                                rssi,
                            )


                # ------------------------------------------------
                # STAZIONE COME RICEVITORE
                # ------------------------------------------------

                if receiver_upper == callsign:

                    received_count += 1

                    if reception_type == "SPECIAL":

                        special_count += 1

                    elif source:

                        if reception_type == "VIA":

                            key = (
                                source_upper +
                                "|VIA|" +
                                (
                                    via_text.upper()
                                    if via_text
                                    else "UNKNOWN"
                                )
                            )

                            update_group(
                                received_groups,
                                key,
                                source,
                                "via",
                                via_text or "UNKNOWN",
                                timestamp,
                                rssi,
                            )

                        else:

                            key = (
                                source_upper +
                                "|DIRECT"
                            )

                            update_group(
                                received_groups,
                                key,
                                source,
                                "direct",
                                None,
                                timestamp,
                                rssi,
                            )


                # ------------------------------------------------
                # TRANSITATO VIA STAZIONE
                # ------------------------------------------------

                is_transit = (
                    callsign in via_upper
                    and source_upper != callsign
                )

                is_station_activity = (
                    source_upper == callsign
                    or receiver_upper == callsign
                    or is_transit
                )

                if is_station_activity:

                    if (
                        first_activity is None
                        or timestamp < first_activity
                    ):
                        first_activity = timestamp

                    if (
                        last_activity is None
                        or timestamp > last_activity
                    ):
                        last_activity = timestamp


                if is_transit:

                    transit_count += 1

                    key = (
                        source_upper +
                        "|" +
                        receiver_upper
                    )

                    if key not in transit_groups:

                        transit_groups[key] = {
                            "source":
                                source,

                            "receiver":
                                receiver or "-",

                            "count":
                                0,

                            "last":
                                None,
                        }

                    item = transit_groups[key]

                    item["count"] += 1

                    if (
                        timestamp
                        and (
                            item["last"] is None
                            or timestamp > item["last"]
                        )
                    ):
                        item["last"] = timestamp


            aggregate_ms = (
                time.perf_counter() - t0
            ) * 1000


            # ====================================================
            # FALLBACK POSIZIONE STORICA
            # ====================================================

            position_source = "PERIOD"

            if latest_position is None:

                fallback = find_last_valid_position(
                    conn,
                    callsign,
                )

                if fallback:

                    latest_position = {
                        "latitude":
                            fallback["latitude"],

                        "longitude":
                            fallback["longitude"],

                        "timestamp":
                            fallback[
                                "position_timestamp"
                            ],

                        "packet_id":
                            fallback[
                                "position_packet_id"
                            ],
                    }

                    latest_symbol = {
                        "symbol_table":
                            fallback[
                                "symbol_table"
                            ],

                        "symbol_code":
                            fallback[
                                "symbol_code"
                            ],

                        "symbol":
                            fallback[
                                "symbol"
                            ],

                        "position_type":
                            fallback[
                                "position_type"
                            ],
                    }

                    position_source = "HISTORICAL"

                else:

                    position_source = "NONE"


            conn.close()


            # ====================================================
            # ORDINAMENTO GRUPPI
            # ====================================================

            def sorted_groups(groups):

                return sorted(
                    groups.values(),
                    key=lambda x:
                        x["last"] or "",
                    reverse=True,
                )


            tx_groups = sorted_groups(
                transmitted_groups
            )

            rx_groups = sorted_groups(
                received_groups
            )

            transit_list = sorted(
                transit_groups.values(),
                key=lambda x:
                    x["last"] or "",
                reverse=True,
            )


            transmitted_direct = [
                x
                for x in tx_groups
                if x["category"] == "direct"
            ]

            transmitted_via = [
                x
                for x in tx_groups
                if x["category"] == "via"
            ]

            received_direct = [
                x
                for x in rx_groups
                if x["category"] == "direct"
            ]

            received_via = [
                x
                for x in rx_groups
                if x["category"] == "via"
            ]


            # ====================================================
            # INFORMAZIONI STAZIONE
            #
            # Stessa logica della Home:
            #
            # 1. commento valido in un packet di posizione
            # 2. status APRS ">"
            # 3. nessuna informazione
            # ====================================================

            station_info = None


            # Prima scelta: packet di posizione.
            for info_packet in source_packets:

                raw = str(
                    info_packet.get("packet") or ""
                )

                if ":" not in raw:
                    continue

                payload = raw.split(":", 1)[1]

                if not payload.startswith(("!", "=", "/", "@")):
                    continue

                candidate = extract_aprs_comment(
                    raw
                )

                if candidate:

                    station_info = candidate
                    break


            # Seconda scelta: STATUS APRS.
            if not station_info:

                for info_packet in source_packets:

                    raw = str(
                        info_packet.get("packet") or ""
                    )

                    if ":" not in raw:
                        continue

                    payload = raw.split(":", 1)[1]

                    if not payload.startswith(">"):
                        continue

                    candidate = extract_aprs_comment(
                        raw
                    )

                    if candidate:

                        station_info = candidate
                        break


            total_ms = (
                time.perf_counter()
                - total_start
            ) * 1000


            # ====================================================
            # RISPOSTA COMPATTA
            # ====================================================

            return self.send_json({

                "callsign":
                    callsign,

                "hours":
                    hours,

                "generated_at":
                    datetime.now(
                        timezone.utc
                    ).isoformat(),

                "summary": {

                    "transmitted_packets":
                        transmitted_count,

                    "received_packets":
                        received_count,

                    "transit_packets":
                        transit_count,

                    "transit_stations":
                        len({
                            x["source"].upper()
                            for x in transit_list
                            if x["source"]
                        }),

                    "transmitted_direct_groups":
                        len(
                            transmitted_direct
                        ),

                    "transmitted_via_groups":
                        len(
                            transmitted_via
                        ),

                    "received_direct_groups":
                        len(
                            received_direct
                        ),

                    "received_via_groups":
                        len(
                            received_via
                        ),

                    "special_packets":
                        special_count,
                },

                "activity": {
                    "first":
                        first_activity,

                    "last":
                        last_activity,
                },

                "position":
                    latest_position,

                "position_source":
                    position_source,

                "symbol":
                    latest_symbol,

                "info":
                    station_info,

                "latest_packet":
                    latest_packet,

                "source_packets":
                    source_packets,

                "transmitted_direct":
                    transmitted_direct,

                "transmitted_via":
                    transmitted_via,

                "received_direct":
                    received_direct,

                "received_via":
                    received_via,

                "transit_groups":
                    transit_list,

                "stats": {

                    "database_packets_scanned":
                        len(rows),

                    "source_packets_returned":
                        len(source_packets),

                    "query_ms":
                        round(
                            query_ms,
                            2
                        ),

                    "aggregate_ms":
                        round(
                            aggregate_ms,
                            2
                        ),

                    "total_ms":
                        round(
                            total_ms,
                            2
                        ),
                },
            })


        if parsed.path == "/api/map":

            query = parse_qs(
                parsed.query
            )

            try:

                hours = float(
                    query.get(
                        "hours",
                        ["24"]
                    )[0]
                )

            except Exception:

                hours = 24

            if hours <= 0:
                hours = 24

            return self.send_json(
                build_map(hours)
            )

        self.send_response(404)
        self.end_headers()


    def send_json(self, data):

        encoded = json.dumps(
            data,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")

        self.send_response(200)

        self.send_header(
            "Content-Type",
            "application/json; charset=utf-8",
        )

        self.send_header(
            "Content-Length",
            str(len(encoded)),
        )

        self.send_header(
            "Access-Control-Allow-Origin",
            "*",
        )

        self.send_header(
            "Cache-Control",
            "no-store",
        )

        self.end_headers()

        self.wfile.write(encoded)


    def log_message(self, fmt, *args):
        return


# ============================================================
# START
# ============================================================

print()
print("=" * 76)
print(" LoRa APRS V2 API")
print("=" * 76)
print(f"Database : {DB_FILE}")
print(f"Parser   : {V1_REFERENCE}")
print(f"Listen   : http://{HOST}:{PORT}")
print()
print(
    f"Test     : "
    f"http://127.0.0.1:{PORT}/api/map?hours=168"
)
print("=" * 76)
print()

server = ThreadingHTTPServer(
    (HOST, PORT),
    Handler,
)

server.serve_forever()
