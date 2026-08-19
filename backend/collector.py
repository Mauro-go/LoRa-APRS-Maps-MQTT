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


def receiver_callsign_for(mqtt_name):
    cfg = RECEIVERS.get(mqtt_name, {})
    return cfg.get("callsign", mqtt_name)


def receiver_enabled(mqtt_name):
    cfg = RECEIVERS.get(mqtt_name)
    if cfg is None:
        # Unknown MQTT receiver names are still collected.
        # This makes troubleshooting easier and avoids silently losing packets.
        return True
    return bool(cfg.get("enabled", True))


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


def save_packet(topic, packet):
    parts = topic.split("/")

    # Expected structure:
    # lora_aprs/RECEIVER/CALLSIGN
    if len(parts) < 3:
        logging.warning("Topic MQTT non riconosciuto: %s", topic)
        return

    receiver = parts[1]

    if not receiver_enabled(receiver):
        return

    receiver_callsign = receiver_callsign_for(receiver)

    if ">" in packet:
        callsign = packet.split(">", 1)[0].strip()
    else:
        callsign = parts[2].strip()

    if not callsign:
        return

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


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        logging.info("MQTT connesso a %s:%s", MQTT_HOST, MQTT_PORT)
        client.subscribe(MQTT_TOPIC)
        logging.info("Sottoscritto a %s", MQTT_TOPIC)
    else:
        logging.error("MQTT connessione fallita: %s", reason_code)


def on_message(client, userdata, msg):
    try:
        packet = msg.payload.decode("utf-8", errors="replace").strip()

        if not packet:
            return

        save_packet(msg.topic, packet)

        logging.info("%s | %s", msg.topic, packet)

        print(
            f"{msg.topic} | {packet}",
            flush=True
        )

    except Exception:
        logging.exception("Errore nella gestione del pacchetto")


def main():
    init_database()

    logging.info("Avvio collector MQTT")

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id="lora-aprs-collector",
    )

    if MQTT_USER:
        client.username_pw_set(
            MQTT_USER,
            MQTT_PASSWORD,
        )

    client.on_connect = on_connect
    client.on_message = on_message

    client.connect(
        MQTT_HOST,
        int(MQTT_PORT),
        60,
    )

    client.loop_forever()


if __name__ == "__main__":
    main()
