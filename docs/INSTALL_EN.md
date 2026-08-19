# LoRa APRS Maps MQTT

## Installation Guide --- English

> **Version:** LoRa APRS Maps MQTT v1.0\
> **Goal:** complete installation starting from a clean Debian system.

------------------------------------------------------------------------

Official repository:

``` text
https://github.com/Mauro-go/LoRa-APRS-Maps-MQTT
```

Reference LoRa APRS iGate firmware:

``` text
https://github.com/richonguzman/LoRa_APRS_iGate
```

------------------------------------------------------------------------

# 1. Purpose

LoRa APRS Maps MQTT raccoglie via MQTT i pacchetti ricevuti da uno o più
iGate LoRa APRS, li memorizza in SQLite e li rende disponibili
attraverso una API e una interfaccia web.

L'installazione finale sarà composta da:

``` text
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
       ├── Mappa APRS
       ├── Dettaglio stazioni
       ├── Dashboard
       └── Mappe di copertura
```

Il sistema è progettato per gestire un numero variabile di ricevitori.
Non devono esistere pagine o porzioni di codice dedicate a nominativi
specifici.

------------------------------------------------------------------------

# 2. Requirements

This procedure starts from a clean Debian installation.

Occorrono:

-   Debian 12 o Debian 13;
-   accesso `root` oppure un utente con `sudo`;
-   collegamento Internet durante l'installazione;
-   almeno un LoRa APRS iGate con MQTT abilitato;
-   accesso a un broker MQTT.

Il broker MQTT può essere:

-   sulla stessa macchina di LoRa APRS Maps MQTT;
-   su un altro server della rete;
-   su un server remoto raggiungibile.

The following examples assume a `root` shell.

------------------------------------------------------------------------

# 3. Update Debian

Update the package index:

``` bash
apt update
```

Upgrade the system:

``` bash
apt upgrade -y
```

------------------------------------------------------------------------

# 4. Install required packages

Installare Python, SQLite, Apache, client MQTT e gli strumenti di base:

``` bash
apt install -y \
    python3 \
    python3-paho-mqtt \
    sqlite3 \
    apache2 \
    mosquitto-clients \
    git \
    curl
```

Check Python:

``` bash
python3 --version
```

Check SQLite:

``` bash
sqlite3 --version
```

Check Apache:

``` bash
systemctl status apache2 --no-pager
```

Apache should show:

``` text
active (running)
```

------------------------------------------------------------------------

# 5. Optional Mosquitto broker installation

## 5.1 Se esiste già un broker MQTT

Se gli iGate pubblicano già verso un broker MQTT funzionante, saltare
questo capitolo e andare al capitolo 6.

## 5.2 Broker sulla stessa macchina

Installare Mosquitto:

``` bash
apt install -y mosquitto
```

Abilitarlo all'avvio:

``` bash
systemctl enable --now mosquitto
```

Controllare:

``` bash
systemctl status mosquitto --no-pager
```

Prima della pubblicazione definitiva documenteremo separatamente anche
una configurazione Mosquitto con autenticazione username/password.

------------------------------------------------------------------------

# 6. Verify MQTT packets

Before installing the application, make sure at least one iGate is
actually publishing MQTT data.

Expected topic structure:

``` text
lora_aprs/NOME_RICEVITORE/CALLSIGN
```

Esempio generico:

``` text
lora_aprs/RX1/IK3ABC-10
lora_aprs/RX2/IQ3XYZ-12
```

To listen to all receivers:

``` bash
mosquitto_sub \
    -h IP_BROKER_MQTT \
    -p 1883 \
    -t 'lora_aprs/+/#' \
    -v
```

If the broker requires authentication:

``` bash
mosquitto_sub \
    -h IP_BROKER_MQTT \
    -p 1883 \
    -u 'UTENTE_MQTT' \
    -P 'PASSWORD_MQTT' \
    -t 'lora_aprs/+/#' \
    -v
```

Quando un iGate riceve un pacchetto APRS devono comparire dati sul
terminale.

**Non proseguire se questo test non funziona.**

Prima verificare:

-   indirizzo del broker;
-   porta MQTT;
-   username/password;
-   configurazione MQTT dell'iGate;
-   rete e firewall;
-   nome del topic.

Stop `mosquitto_sub` with:

``` text
Ctrl+C
```

------------------------------------------------------------------------

# 7. Download the project

Quando la release pubblica sarà disponibile:

``` bash
cd /opt
git clone https://github.com/Mauro-go/LoRa-APRS-Maps-MQTT.git lora-aprs
```

Enter the directory:

``` bash
cd /opt/lora-aprs
```

La struttura è:

``` text
/opt/lora-aprs/
├── README.md
├── LICENSE
├── requirements.txt
├── backend/
├── web/
├── systemd/
├── apache/
└── docs/
```

------------------------------------------------------------------------

# 7.1 File permissions

Before manual tests and before starting the systemd services, verify the
Python file permissions.

Set:

``` bash
chmod 755 /opt/lora-aprs/backend/collector.py
chmod 755 /opt/lora-aprs/backend/api.py
```

Verify:

``` bash
ls -l /opt/lora-aprs/backend/collector.py
ls -l /opt/lora-aprs/backend/api.py
```

Un risultato corretto deve essere simile a:

``` text
-rwxr-xr-x 1 root root ... collector.py
-rwxr-xr-x 1 root root ... api.py
```

Nella nostra installazione di sviluppo era stato necessario correggere i
permessi di `collector.py` per consentirne l'avvio corretto. Per questo
nella procedura v1.0 il controllo dei permessi viene eseguito
esplicitamente.

Se un servizio restituisce `Permission denied`, controllare sempre
anche:

``` bash
namei -l /opt/lora-aprs/backend/collector.py
namei -l /opt/lora-aprs/backend/api.py
```

Questo permette di verificare i permessi non solo del file ma anche di
tutte le directory del percorso.

------------------------------------------------------------------------

# 8. Configuration

La release utilizza un unico file di configurazione.

Create it from the example:

``` bash
cp /opt/lora-aprs/backend/config.example.py \
   /opt/lora-aprs/backend/config.py
```

Edit it:

``` bash
nano /opt/lora-aprs/backend/config.py
```

Example:

``` python
MQTT_HOST = "192.168.1.100"
MQTT_PORT = 1883
MQTT_USER = "username"
MQTT_PASSWORD = "password"
MQTT_TOPIC = "lora_aprs/+/#"

RECEIVERS = {
    "RX1": {
        "callsign": "IK3ABC-10",
        "enabled": True,
    },

    "RX2": {
        "callsign": "IQ3XYZ-12",
        "enabled": True,
    },
}
```

Dove:

-   `MQTT_HOST` = indirizzo del broker MQTT;
-   `MQTT_PORT` = normalmente `1883`;
-   `MQTT_USER` = username MQTT;
-   `MQTT_PASSWORD` = password MQTT;
-   `RX1`, `RX2`, ecc. = nome utilizzato nel topic MQTT;
-   `callsign` = nominativo APRS completo del ricevitore.

Example:

``` text
Topic MQTT:
lora_aprs/MONTE1/IK3XYZ-9
```

Configuration:

``` python
"MONTE1": {
    "callsign": "IK3XYZ-9",
    "enabled": True,
}
```

------------------------------------------------------------------------

# 9. Add receivers

There is no fixed software limit on the number of receivers.

To add one, simply add another entry:

``` python
RECEIVERS = {
    "RX1": {
        "callsign": "IK3ABC-10",
        "enabled": True,
    },

    "RX2": {
        "callsign": "IQ3XYZ-12",
        "enabled": True,
    },

    "RX3": {
        "callsign": "IZ3NEW-10",
        "enabled": True,
    },
}
```

La versione GitHub dovrà utilizzare questa configurazione
automaticamente per:

-   collector;
-   API;
-   dashboard;
-   elenco ricevitori;
-   mappe di copertura.

Non dovrà essere necessario creare una pagina HTML per ogni nuovo
ricevitore.

------------------------------------------------------------------------

# 10. Protect private configuration

`config.py` può contenere password MQTT e **non deve essere pubblicato
su GitHub**.

Il repository includerà:

``` text
config.example.py
```

ma non:

``` text
config.py
```

Il file `.gitignore` dovrà contenere almeno:

``` gitignore
backend/config.py
*.db
*.log
__pycache__/
*.pyc
```

Verificare sempre prima di un commit che `config.py` non sia incluso.

------------------------------------------------------------------------

# 11. SQLite database

Il collector crea automaticamente il database al primo avvio.

Percorso previsto:

``` text
/opt/lora-aprs/data/lora-aprs.db
```

There is no need to create SQL tables manually.

Per controllare il database:

``` bash
sqlite3 /opt/lora-aprs/data/lora-aprs.db ".tables"
```

Per controllare la struttura:

``` bash
sqlite3 /opt/lora-aprs/data/lora-aprs.db ".schema packets"
```

Per vedere gli ultimi pacchetti:

``` bash
sqlite3 -header -column /opt/lora-aprs/data/lora-aprs.db "
SELECT
    id,
    timestamp,
    receiver_callsign,
    callsign,
    packet
FROM packets
ORDER BY id DESC
LIMIT 10;
"
```

------------------------------------------------------------------------

# 12. Manual collector test

Before installing the systemd service:

``` bash
cd /opt/lora-aprs/backend
python3 collector.py
```

Lasciarlo funzionare finché arriva qualche pacchetto.

In another terminal, check the database:

``` bash
sqlite3 -header -column /opt/lora-aprs/data/lora-aprs.db "
SELECT
    timestamp,
    receiver_callsign,
    callsign
FROM packets
ORDER BY id DESC
LIMIT 10;
"
```

Se compaiono i pacchetti, il percorso:

``` text
iGate -> MQTT -> collector -> SQLite
```

funziona.

Stop the test:

``` text
Ctrl+C
```

------------------------------------------------------------------------

# 13. API network configuration and manual test

Before starting `api.py`, choose the listening address carefully.

## 13.1 Apache and API on the same server --- recommended

Se Apache e `api.py` si trovano sullo stesso server, usare:

``` python
API_HOST = "127.0.0.1"
API_PORT = 8080
```

In questo modo la porta 8080 è accessibile solo localmente.

Apache pubblicherà l'API tramite reverse proxy:

``` text
Browser -> Apache -> 127.0.0.1:8080
```

This is the recommended configuration even when the website is public on
the Internet.

**You do not need to use `0.0.0.0` simply because the web page is
public.**

## 13.2 Reverse proxy on another server

Se Apache o un altro reverse proxy si trova su un server differente da
quello che esegue `api.py`, l'API deve essere raggiungibile dalla rete.

È possibile usare l'indirizzo LAN specifico della macchina:

``` python
API_HOST = "192.168.1.50"
API_PORT = 8080
```

oppure:

``` python
API_HOST = "0.0.0.0"
API_PORT = 8080
```

`0.0.0.0` significa che l'API ascolta su tutte le interfacce di rete
disponibili.

Quando possibile è preferibile legarla all'indirizzo LAN specifico.

Se si usa `0.0.0.0`, proteggere la porta 8080 con firewall e consentire
l'accesso soltanto al reverse proxy o alla rete necessaria.

**Non è consigliato esporre direttamente la porta 8080 su Internet.**

Per un servizio pubblico usare:

``` text
Internet
   |
 HTTPS
   |
Apache / Reverse Proxy
   |
api.py:8080
```

Il reverse proxy potrà quindi puntare, ad esempio, a:

``` apache
ProxyPass        /lora-api/ http://192.168.1.50:8080/api/
ProxyPassReverse /lora-api/ http://192.168.1.50:8080/api/
```

## 13.3 Manual test

Avviare:

``` bash
cd /opt/lora-aprs/backend
python3 api.py
```

Se l'API ascolta su `127.0.0.1`, verificare:

``` bash
curl -s http://127.0.0.1:8080/api/status | python3 -m json.tool
```

Then:

``` bash
curl -s 'http://127.0.0.1:8080/api/packets?limit=10' \
    | python3 -m json.tool
```

E soprattutto verificare una ricerca temporale:

``` bash
curl -s 'http://127.0.0.1:8080/api/packets?hours=8' \
    | python3 -m json.tool
```

`hours=8` deve restituire i pacchetti appartenenti alle ultime otto ore.

Non deve significare semplicemente "gli ultimi N pacchetti".

Interrompere:

``` text
Ctrl+C
```

------------------------------------------------------------------------

# 14. Install systemd services

Il repository include i file:

``` text
systemd/lora-aprs-collector.service
systemd/lora-aprs-api.service
```

Copiarli:

``` bash
cp /opt/lora-aprs/systemd/lora-aprs-collector.service \
   /etc/systemd/system/

cp /opt/lora-aprs/systemd/lora-aprs-api.service \
   /etc/systemd/system/
```

Reload systemd:

``` bash
systemctl daemon-reload
```

Enable the services:

``` bash
systemctl enable --now lora-aprs-collector.service
systemctl enable --now lora-aprs-api.service
```

Controllare:

``` bash
systemctl status lora-aprs-collector.service --no-pager
```

``` bash
systemctl status lora-aprs-api.service --no-pager
```

Both should show:

``` text
active (running)
```

------------------------------------------------------------------------

# 15. Service logs

Collector:

``` bash
journalctl -u lora-aprs-collector.service -f
```

API:

``` bash
journalctl -u lora-aprs-api.service -f
```

Uscire con:

``` text
Ctrl+C
```

------------------------------------------------------------------------

# 16. Install the web frontend

Create the directory:

``` bash
mkdir -p /var/www/html/lora-aprs
```

Copy the frontend:

``` bash
cp -a /opt/lora-aprs/web/. /var/www/html/lora-aprs/
```

Set permissions:

``` bash
find /var/www/html/lora-aprs -type d -exec chmod 755 {} \;
find /var/www/html/lora-aprs -type f -exec chmod 644 {} \;
```

La struttura è:

``` text
/var/www/html/lora-aprs/
├── index.html
├── station.html
├── dashboard.html
├── coverage.html
└── aprs-symbols/
```

La pagina `coverage.html` sarà unica.

Il ricevitore verrà selezionato tramite parametro, ad esempio:

``` text
coverage.html?receiver=IK3ABC-10
```

------------------------------------------------------------------------

# 17. Configure Apache reverse proxy

Enable the proxy modules:

``` bash
a2enmod proxy
a2enmod proxy_http
```

Copiare la configurazione fornita:

``` bash
cp /opt/lora-aprs/apache/lora-aprs.conf \
   /etc/apache2/conf-available/lora-aprs.conf
```

Enable it:

``` bash
a2enconf lora-aprs
```

Check Apache:

``` bash
apache2ctl configtest
```

Expected result:

``` text
Syntax OK
```

Reload Apache:

``` bash
systemctl reload apache2
```

------------------------------------------------------------------------

# 18. Verify the reverse proxy

First test the API directly:

``` bash
curl -s http://127.0.0.1:8080/api/status \
    | python3 -m json.tool
```

Then test it through Apache:

``` bash
curl -s http://127.0.0.1/lora-api/status \
    | python3 -m json.tool
```

Se il primo comando funziona e il secondo no, il problema è nella
configurazione Apache.

------------------------------------------------------------------------

# 18.1 Install APRS symbols

The maps use real APRS symbols through the **OK-DMR/aprs-symbols**
library.

The original project is available on GitHub:

``` text
https://github.com/OK-DMR/aprs-symbols
```

La libreria contiene:

-   `aprs-symbols.js`;
-   `aprs-symbols.css`;
-   sprite PNG APRS nelle dimensioni 24, 48, 64 e 128 pixel.

Le nostre pagine utilizzano il percorso web assoluto:

``` text
/aprs-symbols/
```

perciò i file devono essere disponibili, con la configurazione Apache
standard, in:

``` text
/var/www/html/aprs-symbols/
```

Create the directory:

``` bash
mkdir -p /var/www/html/aprs-symbols
```

Clone the library temporarily:

``` bash
git clone --depth 1 \
    https://github.com/OK-DMR/aprs-symbols.git \
    /tmp/aprs-symbols
```

Copy the JavaScript, CSS and PNG sprites:

``` bash
cp /tmp/aprs-symbols/aprs-symbols.js \
   /var/www/html/aprs-symbols/

cp /tmp/aprs-symbols/aprs-symbols.css \
   /var/www/html/aprs-symbols/

cp /tmp/aprs-symbols/aprs-symbols-*.png \
   /var/www/html/aprs-symbols/
```

Set permissions:

``` bash
chmod 755 /var/www/html/aprs-symbols
chmod 644 /var/www/html/aprs-symbols/*
```

Remove the temporary clone:

``` bash
rm -rf /tmp/aprs-symbols
```

Verify that Apache can serve the files:

``` bash
curl -I http://127.0.0.1/aprs-symbols/aprs-symbols.js
```

e:

``` bash
curl -I http://127.0.0.1/aprs-symbols/aprs-symbols-24-0.png
```

Both should return:

``` text
HTTP/1.1 200 OK
```

È utile verificare anche lo sprite da 48 pixel, utilizzato nelle pagine
di dettaglio:

``` bash
curl -I http://127.0.0.1/aprs-symbols/aprs-symbols-48-0.png
```

Se i simboli sulla mappa risultano mancanti oppure vengono mostrati
marker di fallback, controllare per prima cosa questi URL.

> I simboli APRS sono forniti dal progetto **OK-DMR/aprs-symbols**,
> distribuito con licenza MIT. Gli sprite derivano dal lavoro di OH7LZB,
> come indicato dal progetto originale.

------------------------------------------------------------------------

# 19. Open the map

From a browser:

``` text
http://IP_DEL_SERVER/lora-aprs/
```

La home deve mostrare la mappa APRS.

Dovranno essere disponibili:

``` text
/lora-aprs/index.html
/lora-aprs/dashboard.html
/lora-aprs/station.html?callsign=CALLSIGN
/lora-aprs/coverage.html?receiver=CALLSIGN
```

------------------------------------------------------------------------

# 20. Complete installation test

Before considering the installation complete, check the following in
order:

### 1 --- MQTT

``` bash
mosquitto_sub -h IP_BROKER_MQTT -t 'lora_aprs/+/#' -v
```

Devono arrivare pacchetti.

### 2 --- Collector

``` bash
systemctl is-active lora-aprs-collector.service
```

Risultato:

``` text
active
```

### 3 --- Database

``` bash
sqlite3 /opt/lora-aprs/data/lora-aprs.db \
    "SELECT COUNT(*) FROM packets;"
```

Il numero deve aumentare con l'arrivo dei pacchetti.

### 4 --- API

``` bash
curl -s http://127.0.0.1:8080/api/status
```

Deve rispondere.

### 5 --- Apache

``` bash
curl -s http://127.0.0.1/lora-api/status
```

Deve rispondere.

### 6 --- Web

Aprire:

``` text
http://IP_DEL_SERVER/lora-aprs/
```

### 7 --- Ricevitori

Controllare che tutti i ricevitori configurati compaiano
automaticamente.

### 8 --- Mappa di copertura

Aprire la copertura di ciascun ricevitore e verificare i diversi
intervalli temporali.

------------------------------------------------------------------------

# 21. Add another receiver later

Edit:

``` bash
nano /opt/lora-aprs/backend/config.py
```

Add the new receiver to `RECEIVERS`.

Then:

``` bash
systemctl restart lora-aprs-collector.service
systemctl restart lora-aprs-api.service
```

Non dovrà essere necessario:

-   modificare `index.html`;
-   modificare `dashboard.html`;
-   creare una nuova pagina di copertura;
-   modificare `api.py`;
-   modificare `collector.py`.

Questo è un requisito della v1.0.

------------------------------------------------------------------------

# 22. Backup

## Database

``` bash
cp -a \
    /opt/lora-aprs/data/lora-aprs.db \
    /opt/lora-aprs/data/lora-aprs.db.bak-$(date +%Y%m%d-%H%M%S)
```

## Configurazione

``` bash
cp -a \
    /opt/lora-aprs/backend/config.py \
    /root/lora-aprs-config-$(date +%Y%m%d-%H%M%S).py
```

------------------------------------------------------------------------

# 23. Updating from GitHub

Before any update, back up at least:

``` text
backend/config.py
data/lora-aprs.db
```

La configurazione privata e il database non dovranno essere sovrascritti
da un aggiornamento del repository.

------------------------------------------------------------------------

# 24. Troubleshooting

## Nessun dato MQTT

Provare:

``` bash
mosquitto_sub \
    -h IP_BROKER_MQTT \
    -p 1883 \
    -t 'lora_aprs/+/#' \
    -v
```

Se non arriva nulla, il problema è a monte del programma.

------------------------------------------------------------------------

## MQTT funziona ma il database rimane vuoto

Controllare:

``` bash
journalctl -u lora-aprs-collector.service -n 100 --no-pager
```

Poi provare manualmente:

``` bash
cd /opt/lora-aprs/backend
python3 collector.py
```

------------------------------------------------------------------------

## Il database contiene dati ma la API non risponde

Controllare:

``` bash
systemctl status lora-aprs-api.service --no-pager
```

e:

``` bash
journalctl -u lora-aprs-api.service -n 100 --no-pager
```

------------------------------------------------------------------------

## API diretta funziona ma la pagina web no

Provare:

``` bash
curl -s http://127.0.0.1:8080/api/status
```

e:

``` bash
curl -s http://127.0.0.1/lora-api/status
```

Se il primo funziona e il secondo no:

``` bash
apache2ctl configtest
```

e:

``` bash
journalctl -u apache2 -n 100 --no-pager
```

------------------------------------------------------------------------

## Una stazione non appare sulla mappa

Controllare prima se esiste nel database:

``` bash
sqlite3 -header -column /opt/lora-aprs/data/lora-aprs.db "
SELECT
    timestamp,
    receiver_callsign,
    callsign,
    packet
FROM packets
WHERE callsign='CALLSIGN'
ORDER BY id DESC
LIMIT 20;
"
```

Poi controllare il parsing tramite API:

``` bash
curl -s 'http://127.0.0.1:8080/api/packets?hours=8' \
    | python3 -m json.tool
```

------------------------------------------------------------------------

# 25. Security

Before publishing configurations or opening GitHub issues:

**DO NOT publish:**

-   password Wi-Fi;
-   password MQTT;
-   username riservati;
-   APRS-IS passcode;
-   token;
-   API key;
-   backup completi degli iGate contenenti credenziali.

Quando si allegano log o configurazioni a una Issue GitHub, controllarli
prima.

------------------------------------------------------------------------

# 26. Final installation check

The final test is:

``` text
DEBIAN PULITA
      ↓
seguo INSTALL_IT.md
      ↓
configuro MQTT + ricevitori
      ↓
avvio i servizi
      ↓
apro il browser
      ↓
MAPPA FUNZIONANTE
```

L'utente non deve modificare il sorgente Python o JavaScript per
configurare la propria installazione.

Deve essere sufficiente modificare il file di configurazione.

------------------------------------------------------------------------

## LoRa APRS Maps MQTT

Open-source project created in the spirit of amateur-radio
experimentation and knowledge sharing.

**73 de IV3SCP**
