#!/usr/bin/env python3

import logging
import os
import sqlite3
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

from config import (
    DATA_DIR,
    DB_FILE,
    LOG_FILE,
    MQTT_HOST,
    MQTT_PORT,
    MQTT_USER,
    MQTT_PASSWORD,
    MQTT_TOPIC,
    RECEIVERS,
)


os.makedirs(DATA_DIR, exist_ok=True)

logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s | %(message)s",
)


def normalize(value):
    return str(value or "").strip().upper()


def callsign_base(callsign):
    """
    Restituisce il nominativo senza SSID.

    IQ3GO-12  -> IQ3GO
    IV3SCP-10 -> IV3SCP
    IV3XXX    -> IV3XXX

    Il firmware pubblica i pacchetti come:

        lora_aprs/<CALLSIGN_SENZA_SSID>/<STAZIONE>

    RX1, RX2, RX3... rimangono invece identificatori
    interni configurati in RECEIVERS.
    """
    return normalize(callsign).split("-", 1)[0]


def build_receiver_maps():
    """
    Costruisce la corrispondenza:

        RX1 -> IQ3GO-12 -> topic IQ3GO
        RX2 -> IV3SCP-10 -> topic IV3SCP

    Nel config l'utente modifica soltanto il callsign.
    """

    topic_map = {}
    receiver_map = {}

    for rx_id, cfg in RECEIVERS.items():

        rx_id = normalize(rx_id)
        callsign = normalize(cfg.get("callsign"))
        enabled = bool(cfg.get("enabled", True))

        if not rx_id or not callsign:
            continue

        topic_name = callsign_base(callsign)

        info = {
            "rx_id": rx_id,
            "callsign": callsign,
            "enabled": enabled,
            "topic_name": topic_name,
        }

        receiver_map[rx_id] = info

        if enabled:

            if topic_name in topic_map:
                raise RuntimeError(
                    "Due ricevitori configurati generano lo stesso "
                    "identificatore MQTT: " + topic_name
                )

            topic_map[topic_name] = info

    return topic_map, receiver_map


TOPIC_RECEIVER_MAP, RECEIVER_MAP = build_receiver_maps()


def init_database():

    conn = sqlite3.connect(DB_FILE)

    conn.execute("""
        CREATE TABLE IF NOT EXISTS packets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp TEXT NOT NULL,
            receiver TEXT NOT NULL,
            receiver_callsign TEXT,
            callsign TEXT NOT NULL,
            topic TEXT NOT NULL,
            packet TEXT NOT NULL
        )
    """)

    columns = [
        row[1]
        for row in conn.execute("PRAGMA table_info(packets)")
    ]

    if "receiver_callsign" not in columns:
        conn.execute(
            "ALTER TABLE packets ADD COLUMN receiver_callsign TEXT"
        )

    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_packets_timestamp
        ON packets(timestamp)
    """)

    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_packets_receiver
        ON packets(receiver)
    """)

    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_packets_receiver_callsign
        ON packets(receiver_callsign)
    """)

    conn.execute("""
        CREATE INDEX IF NOT EXISTS idx_packets_callsign
        ON packets(callsign)
    """)

    conn.commit()
    conn.close()


def resolve_receiver(topic):

    parts = str(topic).split("/")

    # Formato atteso:
    # lora_aprs/IQ3GO/S57PNX-8
    if len(parts) < 3:
        return None

    topic_name = normalize(parts[1])

    return TOPIC_RECEIVER_MAP.get(topic_name)


def save_packet(topic, packet):

    receiver_info = resolve_receiver(topic)

    if receiver_info is None:

        parts = str(topic).split("/")
        topic_name = parts[1] if len(parts) >= 2 else "?"

        logging.warning(
            "Pacchetto ignorato: ricevitore MQTT '%s' "
            "non associato ad alcun RX configurato",
            topic_name,
        )

        return False

    if not receiver_info["enabled"]:
        return False

    parts = str(topic).split("/")

    # Nel database:
    #
    # receiver          = RX1 / RX2 / RX3 ...
    # receiver_callsign = IQ3GO-12 / IV3SCP-10 / ...
    #
    receiver = receiver_info["rx_id"]
    receiver_callsign = receiver_info["callsign"]

    if ">" in packet:
        callsign = packet.split(">", 1)[0].strip()
    elif len(parts) >= 3:
        callsign = parts[2].strip()
    else:
        return False

    if not callsign:
        return False

    timestamp = datetime.now(timezone.utc).isoformat()

    conn = sqlite3.connect(DB_FILE)

    conn.execute(
        """
        INSERT INTO packets
        (
            timestamp,
            receiver,
            receiver_callsign,
            callsign,
            topic,
            packet
        )
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (
            timestamp,
            receiver,
            receiver_callsign,
            callsign,
            topic,
            packet,
        ),
    )

    conn.commit()
    conn.close()

    return True


def on_connect(client, userdata, flags, reason_code, properties):

    if reason_code == 0:

        logging.info("MQTT connesso")

        client.subscribe(MQTT_TOPIC)

        logging.info(
            "Sottoscritto a %s",
            MQTT_TOPIC
        )

        for rx_id, info in RECEIVER_MAP.items():

            logging.info(
                "%s -> callsign=%s -> topic=%s -> enabled=%s",
                rx_id,
                info["callsign"],
                info["topic_name"],
                info["enabled"],
            )

    else:

        logging.error(
            "MQTT connessione fallita: %s",
            reason_code
        )


def on_message(client, userdata, msg):

    try:

        packet = msg.payload.decode(
            "utf-8",
            errors="replace"
        ).strip()

        if not packet:
            return

        saved = save_packet(
            msg.topic,
            packet
        )

        if saved:

            logging.info(
                "%s | %s",
                msg.topic,
                packet
            )

            print(
                f"{msg.topic} | {packet}",
                flush=True
            )

    except Exception:

        logging.exception(
            "Errore nella gestione del pacchetto"
        )


def main():

    init_database()

    if not RECEIVER_MAP:
        raise RuntimeError(
            "Nessun ricevitore valido configurato"
        )

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id="lora-aprs-collector"
    )

    if MQTT_USER:

        client.username_pw_set(
            MQTT_USER,
            MQTT_PASSWORD
        )

    client.on_connect = on_connect
    client.on_message = on_message

    client.connect(
        MQTT_HOST,
        int(MQTT_PORT),
        60
    )

    client.loop_forever()


if __name__ == "__main__":
    main()
