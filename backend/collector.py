#!/usr/bin/env python3

import json
import logging
import os
import sqlite3
from collections import defaultdict, deque
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

RECEIVER_MAP_FILE = os.path.join(DATA_DIR, "receiver-map.json")
PENDING_PER_TOPIC = 500

logging.basicConfig(
    filename=LOG_FILE,
    level=logging.INFO,
    format="%(asctime)s | %(message)s",
)


def normalize(value):
    return str(value or "").strip().upper()


def configured_receivers():
    result = {}

    for rx_id, cfg in RECEIVERS.items():
        rx_id = normalize(rx_id)
        callsign = normalize(cfg.get("callsign"))
        enabled = bool(cfg.get("enabled", True))

        if not rx_id or not callsign:
            continue

        result[rx_id] = {
            "rx_id": rx_id,
            "callsign": callsign,
            "enabled": enabled,
        }

    return result


CONFIGURED_RECEIVERS = configured_receivers()

CALLSIGN_TO_RX = {
    info["callsign"]: rx_id
    for rx_id, info in CONFIGURED_RECEIVERS.items()
    if info["enabled"]
}

TOPIC_TO_RX = {}
PENDING = defaultdict(lambda: deque(maxlen=PENDING_PER_TOPIC))


def load_receiver_map():
    if not os.path.exists(RECEIVER_MAP_FILE):
        return

    try:
        with open(RECEIVER_MAP_FILE, "r", encoding="utf-8") as f:
            saved = json.load(f)

        if not isinstance(saved, dict):
            return

        for topic_name, rx_id in saved.items():
            topic_name = normalize(topic_name)
            rx_id = normalize(rx_id)

            if (
                topic_name
                and rx_id in CONFIGURED_RECEIVERS
                and CONFIGURED_RECEIVERS[rx_id]["enabled"]
            ):
                TOPIC_TO_RX[topic_name] = rx_id

    except Exception:
        logging.exception("Errore lettura %s", RECEIVER_MAP_FILE)


def save_receiver_map():
    tmp_file = RECEIVER_MAP_FILE + ".tmp"

    with open(tmp_file, "w", encoding="utf-8") as f:
        json.dump(TOPIC_TO_RX, f, indent=2, sort_keys=True)

    os.replace(tmp_file, RECEIVER_MAP_FILE)


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


def parse_topic(topic):
    parts = str(topic).split("/")

    if len(parts) < 3:
        return None, None

    return normalize(parts[1]), normalize(parts[2])


def packet_source(packet, fallback=""):
    packet = str(packet or "").strip()

    if ">" in packet:
        return normalize(packet.split(">", 1)[0])

    return normalize(fallback)


def learn_receiver(topic_name, source_callsign):
    rx_id = CALLSIGN_TO_RX.get(source_callsign)

    if not rx_id:
        return None

    existing = TOPIC_TO_RX.get(topic_name)

    if existing and existing != rx_id:
        logging.error(
            "Conflitto mappa MQTT: %s era %s, ora il beacon %s indica %s",
            topic_name,
            existing,
            source_callsign,
            rx_id,
        )
        return None

    if not existing:
        TOPIC_TO_RX[topic_name] = rx_id
        save_receiver_map()

        logging.info(
            "Associazione automatica appresa: %s -> %s -> %s",
            topic_name,
            rx_id,
            CONFIGURED_RECEIVERS[rx_id]["callsign"],
        )

    return rx_id


def insert_packet(rx_id, topic, packet):
    info = CONFIGURED_RECEIVERS.get(rx_id)

    if not info or not info["enabled"]:
        return False

    _, topic_callsign = parse_topic(topic)

    callsign = packet_source(packet, topic_callsign)

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
            rx_id,
            info["callsign"],
            callsign,
            topic,
            packet,
        ),
    )

    conn.commit()
    conn.close()

    return True


def flush_pending(topic_name, rx_id):
    queue = PENDING.get(topic_name)

    if not queue:
        return

    count = 0

    while queue:
        topic, packet = queue.popleft()

        if insert_packet(rx_id, topic, packet):
            count += 1

    if topic_name in PENDING:
        del PENDING[topic_name]

    logging.info(
        "Recuperati %s pacchetti in attesa per %s -> %s",
        count,
        topic_name,
        rx_id,
    )


def process_packet(topic, packet):
    topic_name, topic_callsign = parse_topic(topic)

    if not topic_name:
        logging.warning("Topic MQTT non valido: %s", topic)
        return False

    source_callsign = packet_source(packet, topic_callsign)

    rx_id = TOPIC_TO_RX.get(topic_name)

    if rx_id:
        return insert_packet(rx_id, topic, packet)

    rx_id = learn_receiver(topic_name, source_callsign)

    if rx_id:
        flush_pending(topic_name, rx_id)
        return insert_packet(rx_id, topic, packet)

    PENDING[topic_name].append((topic, packet))

    if len(PENDING[topic_name]) == 1:
        logging.info(
            "Ricevitore MQTT '%s' non ancora identificato: "
            "attendo il beacon di uno dei callsign configurati",
            topic_name,
        )

    return False


def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        logging.info("MQTT connesso")

        client.subscribe(MQTT_TOPIC)

        logging.info("Sottoscritto a %s", MQTT_TOPIC)

        for rx_id, info in CONFIGURED_RECEIVERS.items():
            logging.info(
                "%s -> callsign=%s -> enabled=%s",
                rx_id,
                info["callsign"],
                info["enabled"],
            )

        for topic_name, rx_id in TOPIC_TO_RX.items():
            logging.info(
                "Mappa MQTT già nota: %s -> %s",
                topic_name,
                rx_id,
            )

    else:
        logging.error("MQTT connessione fallita: %s", reason_code)


def on_message(client, userdata, msg):
    try:
        packet = msg.payload.decode(
            "utf-8",
            errors="replace"
        ).strip()

        if not packet:
            return

        saved = process_packet(msg.topic, packet)

        if saved:
            logging.info("%s | %s", msg.topic, packet)
            print(f"{msg.topic} | {packet}", flush=True)

    except Exception:
        logging.exception("Errore nella gestione del pacchetto")


def main():
    init_database()
    load_receiver_map()

    if not CONFIGURED_RECEIVERS:
        raise RuntimeError("Nessun ricevitore valido configurato")

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
