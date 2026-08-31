<img width="1872" height="966" alt="immagine" src="https://github.com/user-attachments/assets/a5b60fdd-593e-4802-b15a-4b48373972f4" />
<img width="1917" height="1077" alt="immagine" src="https://github.com/user-attachments/assets/94171a88-0488-449c-a277-58680b3f7fe6" />


# LoRa APRS Maps MQTT

**LoRa APRS Maps MQTT** is a web-based monitoring, mapping and coverage analysis system for **LoRa APRS iGates** using MQTT.

The project collects APRS packets received by one or more LoRa iGates, stores them in a local SQLite database and displays stations, paths, reception information and coverage on an interactive web map.

The project was created in the spirit of amateur radio experimentation and sharing.

> **Current release: v2.0**
>
> Stable version released in August 2026.  
> Future updates and major changes will be listed in the Version history below.
---
> ### Android app updates — August 2026

- Fixed live packet playback after app resume / phone standby
- Added APRS weather data to station details
- Updated Android APK
---

## Version history

### v2.0 — August 2026

Major update of **LoRa APRS Maps MQTT**.

- Dynamic configuration for multiple LoRa APRS receivers
- Improved APRS packet parsing and position handling
- Support for compressed, uncompressed and timestamped APRS positions
- Improved APRS symbols and overlays
- Interactive station map with reception paths
- Direct and digipeated reception visualization
- Station information extracted from APRS packets
- Mobile station tracks and speed information
- Dynamic coverage maps for configured receivers
- Station and receiver hover popups
- Improved station details and statistics
- Italian / English interface
- Improved API structure and performance
- Configuration separated from application code
- Automatic SQLite database maintenance
- Weekly database backup and cleanup using a systemd timer
- Configurable packet retention with automatic removal of old data

---

## Features

- Reception of LoRa APRS packets via **MQTT**
- Support for multiple LoRa APRS iGates
- Local **SQLite** packet database
- Automatic database backup and maintenance
- 7-day packet retention to keep the active database compact
- Database backups retained for 90 days
- Weekly maintenance through a systemd timer
- Python HTTP API
- Interactive APRS map based on **Leaflet / OpenStreetMap**
- Real APRS symbols and overlays
- Station callsign labels
- Station detail pages
- Packet reception history
- Direct and digipeated (`via`) reception analysis
- Mobile station track history
- Information for individual points along a track
- Time filters for historical data
- Receiver coverage maps
- Lines between directly received stations and the receiving iGate
- Receiver statistics and dashboard
- APRS weather data display when available
- Web interface usable from desktop and mobile browsers

---

## How it works

```text
LoRa APRS iGate
       │
       │ MQTT
       ▼
   MQTT Broker
       │
       ▼
  collector.py
       │
       ▼
 SQLite Database
       │
       ▼
     api.py
       │
       ▼
 Apache Web Server
       │
       ├── APRS Map
       ├── Station Details
       ├── Coverage Maps
       └── Dashboard
```

Each LoRa APRS iGate sends the packets it receives to an MQTT broker.

The MQTT collector subscribes to the configured topics and stores the received packets together with timestamp, source station and receiving iGate.

The API reads the database, decodes APRS information and provides the data required by the web interface.

A weekly database maintenance task automatically creates a compressed backup of the SQLite database before removing packet records older than 7 days. Database backups are retained for 90 days. The maintenance task is managed by a systemd timer.

---

## Screenshots

Screenshots will be added before the first public release.

The main interface includes:

- live and historical APRS map
- station tracks
- direct/via reception information
- receiver coverage visualization
- station statistics
- weather information
- system dashboard

---

## LoRa APRS iGate firmware

This project was developed to work with MQTT data produced by the excellent:

**LoRa_APRS_iGate** firmware by richonguzman

https://github.com/richonguzman/LoRa_APRS_iGate

LoRa APRS Maps MQTT is a separate and independent project and is not affiliated with the original firmware author.

Many thanks to the LoRa APRS community and to the developers who make their work available to radio amateurs.

---

## MQTT

Multiple receivers can be handled by using different MQTT receiver names.

Example topic structure:

```text
lora_aprs/RX1/CALLSIGN
lora_aprs/RX2/CALLSIGN
```

The collector can subscribe to:

```text
lora_aprs/+/#
```

Example receiver configuration:

```text
RX1 -> CALLSIGN-10
RX2 -> CALLSIGN-12
```

The public version of the project will use a configuration file so that callsigns, MQTT server details and receivers can be changed without modifying the program source.

---


## RSSI Support

RSSI (Received Signal Strength Indicator) values can be displayed on the main map and on the coverage map.

**Important:** RSSI data is available only when using a modified LoRa APRS iGate firmware that includes the received signal level in the MQTT topic.

The expected MQTT topic format is:

```text
lora_aprs/RECEIVER/CALLSIGN/RSSI
```

Example:

```text
lora_aprs/IV3SCP/S58W-13/-92
```

The standard three-level MQTT topic remains fully supported:

```text
lora_aprs/RECEIVER/CALLSIGN
```

When using the standard iGate firmware, **LoRa APRS Maps MQTT continues to work normally**, but RSSI values are not available and therefore are not displayed.

---

## APRS support

The backend includes APRS parsing required by the map, including:

- compressed positions
- uncompressed positions
- APRS symbol table
- APRS symbol code
- alternate table overlays
- third-party packets
- digipeater path analysis

The frontend uses the decoded information returned by the API rather than independently recalculating station coordinates.

---

## Station tracks

Historical packets can be used to reconstruct the path of a moving APRS station.

Track points retain their reception information, allowing the web interface to show which iGate received a particular position and whether the packet was received directly or through a digipeater.

Historical queries are based on packet timestamps, so selecting a time period retrieves the packets belonging to that period rather than simply requesting a fixed number of recent packets.

---

## Coverage maps

A coverage page can be generated for each receiver.

Only stations received **directly** by the selected iGate are used for the coverage visualization.

Lines are drawn between the receiver and directly received stations, making it possible to obtain a simple visual representation of actual LoRa APRS reception.

Coverage information can also be filtered by time period.

---

## Weather

When APRS weather information is present in transmitted packets, the station page can display values such as:

- temperature
- humidity
- atmospheric pressure
- wind direction
- wind speed
- wind gust
- rain

---

## Requirements

Typical installation:

- Debian Linux
- Python 3
- Paho MQTT
- SQLite3
- Apache HTTP Server
- MQTT broker such as Mosquitto
- one or more LoRa APRS iGates with MQTT enabled

The MQTT broker may run on the same server or on another machine.

---

## Installation

**Manual installation:** Follow the instructions in `docs/INSTALL_IT.md` or `docs/INSTALL_EN.md`.

**Automatic installation:** Download `docs/install.sh`, make it executable, and run it as root:

```bash
chmod +x install.sh
./install.sh
```

The automatic installer configures the required Debian packages, backend, web interface, Apache reverse proxy, systemd services and weekly database maintenance.

The installation process includes:

1. installing the required packages;
2. downloading the current repository from GitHub;
3. configuring the MQTT broker;
4. configuring one or more LoRa APRS receivers;
5. creating the application configuration;
6. installing the collector and API services;
7. installing the web interface and APRS symbols;
8. configuring Apache;
9. installing and enabling automatic weekly database maintenance;
10. running final service and connectivity checks.

The database maintenance system creates a compressed backup before cleanup, keeps the active packet history limited to 7 days and retains database backups for 90 days.

Italian documentation will be available in:

```text
docs/INSTALL_IT.md
```

English documentation will be available in:

```text
docs/INSTALL_EN.md
```

---

## Repository structure

```text
LoRa-APRS-Maps-MQTT/
├── README.md
│
├── backend/
│   ├── collector.py
│   ├── api.py
│   └── config.example.py
│
├── web/
│   ├── index.html
│   ├── station.html
│   ├── dashboard.html
│   └── coverage.html
│
├── maintenance/
│   └── dbmaintenance.sh
│
├── systemd/
│   ├── lora-aprs-collector.service
│   ├── lora-aprs-api.service
│   ├── lora-aprs-dbmaintenance.service
│   └── lora-aprs-dbmaintenance.timer
│
├── apache/
│   └── lora-aprs.conf
│
├── data/
│   └── .gitkeep
│
└── docs/
    ├── INSTALL_IT.md
    ├── INSTALL_EN.md
    └── install.sh
```

The automatic installer uses these repository files to create the runtime installation under `/opt/lora-aprs`, `/var/www/html/lora-aprs` and `/etc/systemd/system`.

---

## Security

**Never publish your private configuration file.**

The public repository must not contain:

- Wi-Fi passwords
- MQTT passwords
- private MQTT credentials
- APRS-IS passcodes
- API keys or tokens
- private configuration backups
- other authentication information

Example configuration files will contain placeholders only.

---

## Project philosophy

This project started as a practical experiment to collect and visualize the packets received by multiple LoRa APRS iGates.

It gradually grew into a complete monitoring system with station history, APRS paths, coverage visualization and receiver statistics.

The aim of publishing it is simple: allow other radio amateurs to experiment with it, improve it and adapt it to their own LoRa APRS networks.

Amateur radio has always grown through experimentation, technical curiosity and sharing knowledge.

**73!**

---

## Credits

Developed by **IV3SCP**.

Created with the help of the amateur radio community and open-source software.

Special thanks to the developers of:

- LoRa_APRS_iGate
- Leaflet
- OpenStreetMap
- Eclipse Paho MQTT
- Mosquitto
- Python
- SQLite
- Apache HTTP Server

---

## License

The license for the first public release is currently being selected.

A `LICENSE` file will be included before the first stable release.

---

## Contributing

Contributions, testing, bug reports and suggestions from the amateur radio community will be welcome once the first public release is available.

If you test the project with different LoRa APRS hardware, network configurations or iGate installations, feedback will be especially useful.

---

## Author

**IV3SCP**

Amateur Radio / LoRa APRS experimentation
https://loramaps.iv3scp.it
**73 de IV3SCP**
