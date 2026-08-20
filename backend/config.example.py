#!/usr/bin/env python3

# ============================================================
# LoRa APRS Maps MQTT - CONFIGURAZIONE
# ============================================================

BASE_DIR = "/opt/lora-aprs"
DATA_DIR = f"{BASE_DIR}/data"
DB_FILE = f"{DATA_DIR}/lora-aprs.db"
LOG_FILE = f"{DATA_DIR}/packets.log"


# ============================================================
# MQTT
# ============================================================

MQTT_HOST = ""
MQTT_PORT = 1883
MQTT_USER = ""
MQTT_PASSWORD = ""
MQTT_TOPIC = "lora_aprs/+/#"


# ============================================================
# API
# ============================================================

API_HOST = "127.0.0.1"
API_PORT = 8080

# Numero massimo di pacchetti restituiti quando si usa ?hours=
MAX_HISTORY_PACKETS = 50000


# ============================================================
# RICEVITORI
# ============================================================
#
# RX1, RX2, RX3... sono identificatori interni.
# NON modificare i nomi RX1, RX2, ecc.
#
# Inserire solamente il callsign APRS completo del ricevitore.
#
# Esempio:
#
# "RX1": {
#     "callsign": "IV3XXX-10",
#     "enabled": True,
# },
#
# Il collector ricava automaticamente il nome MQTT dal callsign:
#
# IV3XXX-10 -> IV3XXX
#
# e associa quindi il topic:
#
# lora_aprs/IV3XXX/...
#
# ============================================================

RECEIVERS = {

    # Config RX1
    "RX1": {
        "callsign": "",
        "enabled": True,
    },

    # Config RX2
    "RX2": {
        "callsign": "",
        "enabled": True,
    },

    # Config RX3
    # "RX3": {
    #     "callsign": "",
    #     "enabled": True,
    # },

}
