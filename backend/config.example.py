#!/usr/bin/env python3
"""
LoRa APRS Maps MQTT - example configuration.

Copy this file to config.py and edit the values for your installation:

    cp config.example.py config.py

Never publish config.py if it contains credentials.
"""

BASE_DIR = "/opt/lora-aprs"
DATA_DIR = f"{BASE_DIR}/data"
DB_FILE = f"{DATA_DIR}/lora-aprs.db"
LOG_FILE = f"{DATA_DIR}/packets.log"

# MQTT broker
MQTT_HOST = "192.168.1.100"
MQTT_PORT = 1883
MQTT_USER = ""
MQTT_PASSWORD = ""
MQTT_TOPIC = "lora_aprs/+/#"

# API
# Recommended when Apache and API run on the same host:
API_HOST = "127.0.0.1"
API_PORT = 8080

# Maximum number of packets returned by a historical ?hours= query.
# Increase it if you have very high packet traffic and need long tracks.
MAX_HISTORY_PACKETS = 50000

# Receiver configuration.
#
# The dictionary key is the receiver name used in the MQTT topic:
#
#   lora_aprs/RX1/CALLSIGN
#
# "callsign" is the full APRS callsign of the receiving iGate.
RECEIVERS = {
    "RX1": {
        "callsign": "CALLSIGN-10",
        "enabled": True,
    },

    # Add as many receivers as required:
    #
    # "RX2": {
    #     "callsign": "CALLSIGN-12",
    #     "enabled": True,
    # },
}
