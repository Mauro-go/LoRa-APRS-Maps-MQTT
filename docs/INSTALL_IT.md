# LoRa APRS Maps MQTT

## Guida di installazione --- Italiano

> **Versione:** LoRa APRS Maps MQTT v1.0\
> **Obiettivo:** installazione completa partendo da una Debian pulita.

------------------------------------------------------------------------

Repository ufficiale:

``` text
https://github.com/Mauro-go/LoRa-APRS-Maps-MQTT
```

Firmware LoRa APRS iGate di riferimento:

``` text
https://github.com/richonguzman/LoRa_APRS_iGate
```

------------------------------------------------------------------------

# 1. Obiettivo

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

# 2. Requisiti

Questa procedura parte da una installazione Debian pulita.

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

Negli esempi seguenti si assume di operare come `root`.

------------------------------------------------------------------------

# 3. Aggiornamento di Debian

Aggiornare l'indice dei pacchetti:

``` bash
apt update
```

Aggiornare il sistema:

``` bash
apt upgrade -y
```

------------------------------------------------------------------------

# 4. Installazione dei pacchetti necessari

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

Verificare Python:

``` bash
python3 --version
```

Verificare SQLite:

``` bash
sqlite3 --version
```

Verificare Apache:

``` bash
systemctl status apache2 --no-pager
```

Apache deve risultare:

``` text
active (running)
```

------------------------------------------------------------------------

# 5. Installazione opzionale del broker Mosquitto

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

# 6. Verifica dei pacchetti MQTT

Prima di installare il programma è fondamentale verificare che almeno un
iGate stia realmente inviando dati MQTT.

La struttura prevista dei topic è:

``` text
lora_aprs/NOME_RICEVITORE/CALLSIGN
```

Esempio generico:

``` text
lora_aprs/IK3ABC/IK3ABC-10
lora_aprs/IQ3XYZ/IQ3XYZ-12
```

Il secondo elemento del topic è il nome MQTT pubblicato dall'iGate e non
corrisponde necessariamente agli identificatori interni `RX1`, `RX2`,
ecc.

Per ascoltare tutti i ricevitori:

``` bash
mosquitto_sub \
    -h IP_BROKER_MQTT \
    -p 1883 \
    -t 'lora_aprs/+/#' \
    -v
```

Se il broker richiede autenticazione:

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

Interrompere `mosquitto_sub` con:

``` text
Ctrl+C
```

------------------------------------------------------------------------

# 7. Download del progetto

Il repository può essere clonato in qualunque directory scelta
dall'amministratore:

``` text
https://github.com/Mauro-go/LoRa-APRS-Maps-MQTT
```

Il clone crea la directory `LoRa-APRS-Maps-MQTT/`. La posizione del
clone non è importante ai fini dell'installazione.

La struttura del repository è:

``` text
LoRa-APRS-Maps-MQTT/
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

# 7.1 Destinazione dei file e permessi

La posizione in cui è stato clonato il repository non determina la
posizione finale dei file installati.

Destinazioni previste:

``` text
backend/
    destinazione: /opt/lora-aprs/backend/
    file:
        collector.py
        api.py
        config.py

web/
    destinazione: /var/www/html/lora-aprs/
    copiare tutto il contenuto della directory web/

data/
    creare la directory:
        /opt/lora-aprs/data/

systemd/
    destinazione: /etc/systemd/system/
    file:
        lora-aprs-collector.service
        lora-aprs-api.service

apache/
    contiene la configurazione di riferimento per Apache
```

## Permessi

La procedura descritta in questa guida viene eseguita come `root`.

I due programmi Python del backend devono essere eseguibili e devono
risultare:

``` text
-rwxr-xr-x  root root  collector.py
-rwxr-xr-x  root root  api.py
```

quindi con permessi `755`.

I percorsi sono:

``` text
/opt/lora-aprs/backend/collector.py
/opt/lora-aprs/backend/api.py
```

La directory:

``` text
/opt/lora-aprs/data/
```

deve esistere e deve consentire ai servizi di creare e modificare i file
runtime del progetto, in particolare:

``` text
lora-aprs.db
packets.log
receiver-map.json
```

Nell'installazione descritta in questa guida, eseguita come `root` e con
i servizi systemd forniti dal progetto, directory e file runtime possono
essere mantenuti di proprietà `root:root`.

**IMPORTANTE:** permessi errati su `collector.py`, `api.py` oppure sulla
directory `/opt/lora-aprs/data/` possono impedire l'avvio dei servizi o
la scrittura dei pacchetti nel database e nei log. In caso di problemi,
questi permessi sono quindi una delle prime verifiche da effettuare.

------------------------------------------------------------------------

# 8. Configurazione

Il backend utilizza un unico file locale:

``` text
/opt/lora-aprs/backend/config.py
```

Il repository fornisce `backend/config.example.py`, da usare come
modello per creare `config.py`.

Le impostazioni principali sono:

``` python
MQTT_HOST = "192.168.1.100"
MQTT_PORT = 1883
MQTT_USER = "username"
MQTT_PASSWORD = "password"
MQTT_TOPIC = "lora_aprs/+/#"

API_HOST = "127.0.0.1"
API_PORT = 8080

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

Significato:

-   `MQTT_HOST` e `MQTT_PORT`: broker MQTT;
-   `MQTT_USER` e `MQTT_PASSWORD`: credenziali, se richieste;
-   **`MQTT_TOPIC = "lora_aprs/+/#"`: NON MODIFICARE.** Questo valore
    deve rimanere invariato. Il wildcard `+` permette al collector di
    ricevere i pacchetti provenienti da tutti gli iGate e di associarli
    automaticamente ai ricevitori `RX1`, `RX2`, `RX3`, ecc. Non inserire
    qui il callsign del ricevitore né il nome MQTT dell'iGate;
-   `API_HOST` e `API_PORT`: indirizzo e porta di ascolto dell'API;
-   `RX1`, `RX2`, `RX3`...: identificatori interni del programma;
-   `callsign`: nominativo APRS completo del ricevitore;
-   `enabled`: abilita o disabilita il ricevitore.

## 8.1 RX1/RX2 e nome MQTT

`RX1`, `RX2`, `RX3`... **non sono i nomi presenti nel topic MQTT** e non
devono essere sostituiti con essi.

Se, ad esempio, l'iGate pubblica:

``` text
lora_aprs/IK3ABC/IK3ABC-10
```

la configurazione rimane:

``` python
"RX1": {
    "callsign": "IK3ABC-10",
    "enabled": True,
}
```

Il collector riconosce il beacon del callsign configurato, associa
automaticamente il nome MQTT (`IK3ABC` nell'esempio) all'identificatore
interno `RX1` e memorizza l'associazione in:

``` text
/opt/lora-aprs/data/receiver-map.json
```

L'associazione viene riutilizzata ai successivi riavvii.

Prima che il ricevitore sia identificato, il collector può mantenere
temporaneamente in attesa i pacchetti provenienti da quel nome MQTT.
Quando arriva il beacon del callsign configurato, l'associazione viene
appresa e i pacchetti in attesa vengono recuperati.

------------------------------------------------------------------------

# 9. Aggiungere ricevitori

Per aggiungere un ricevitore si aggiunge un nuovo identificatore interno
progressivo (`RX3`, `RX4`, ecc.) e si specifica il relativo callsign
APRS.

Esempio:

``` python
"RX3": {
    "callsign": "IZ3NEW-10",
    "enabled": True,
}
```

Non è necessario conoscere o inserire nel `config.py` il nome MQTT usato
dall'iGate: il collector lo apprende automaticamente dal beacon.

La configurazione dei ricevitori viene utilizzata dal sistema per
collector, API, dashboard, menu dei ricevitori e pagina unica
`coverage.html`.

Non è necessario creare una pagina HTML separata per ogni ricevitore.

------------------------------------------------------------------------

# 10. Protezione della configurazione

`config.py` può contenere password MQTT e **non deve essere pubblicato
su GitHub**.

Il repository include:

``` text
config.example.py
```

ma non:

``` text
config.py
```

Il file `.gitignore` deve contenere almeno:

``` gitignore
backend/config.py
*.db
*.log
__pycache__/
*.pyc
```

Verificare sempre prima di un commit che `config.py` non sia incluso.

------------------------------------------------------------------------

# 11. Database SQLite

Il collector crea automaticamente il database al primo avvio.

Percorso previsto:

``` text
/opt/lora-aprs/data/lora-aprs.db
```

Non sarà necessario creare manualmente tabelle SQL.

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

# 12. Test manuale del collector

Prima di installare il servizio systemd:

``` bash
cd /opt/lora-aprs/backend
python3 collector.py
```

Lasciarlo funzionare finché arriva qualche pacchetto.

In un altro terminale controllare il database:

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

Interrompere il test:

``` text
Ctrl+C
```

------------------------------------------------------------------------

# 13. Configurazione di rete e test manuale dell'API

Prima di avviare `api.py` è importante scegliere correttamente
l'indirizzo di ascolto.

## 13.1 Apache e API sulla stessa macchina --- configurazione consigliata

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

Questa è la configurazione consigliata anche se il sito web viene
pubblicato su Internet.

**Non è necessario usare `0.0.0.0` solo perché la pagina web è
pubblica.**

## 13.2 Reverse proxy su una macchina diversa

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

## 13.3 Test manuale

Avviare:

``` bash
cd /opt/lora-aprs/backend
python3 api.py
```

Se l'API ascolta su `127.0.0.1`, verificare:

``` bash
curl -s http://127.0.0.1:8080/api/status | python3 -m json.tool
```

Poi:

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

# 14. Installazione dei servizi systemd

Il repository include i file:

``` text
systemd/lora-aprs-collector.service
systemd/lora-aprs-api.service
```

I file `.service` presenti nella directory `systemd/` del repository
devono essere installati in:

``` text
/etc/systemd/system/
```

Ricaricare systemd:

``` bash
systemctl daemon-reload
```

Abilitare i servizi:

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

Entrambi devono risultare:

``` text
active (running)
```

------------------------------------------------------------------------

# 15. Log dei servizi

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

# 16. Installazione delle pagine web

Creare la directory:

``` bash
mkdir -p /var/www/html/lora-aprs
```

Copiare il frontend:

Il contenuto della directory `web/` del repository deve essere
installato nella directory pubblica:

``` text
/var/www/html/lora-aprs/
```

Impostare i permessi:

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

La pagina `coverage.html` è unica.

Il ricevitore viene selezionato tramite parametro, ad esempio:

``` text
coverage.html?receiver=IK3ABC-10
```

------------------------------------------------------------------------

# 17. Configurazione Apache per l'API

Abilitare i moduli proxy:

``` bash
a2enmod proxy
a2enmod proxy_http
```

Copiare la configurazione fornita:

``` bash
cp /opt/lora-aprs/apache/lora-aprs.conf \
   /etc/apache2/conf-available/lora-aprs.conf
```

Abilitarla:

``` bash
a2enconf lora-aprs
```

Controllare Apache:

``` bash
apache2ctl configtest
```

Il risultato deve essere:

``` text
Syntax OK
```

Ricaricare Apache:

``` bash
systemctl reload apache2
```

------------------------------------------------------------------------

# 18. Verifica del reverse proxy

Prima testare direttamente l'API:

``` bash
curl -s http://127.0.0.1:8080/api/status \
    | python3 -m json.tool
```

Poi attraverso Apache:

``` bash
curl -s http://127.0.0.1/lora-api/status \
    | python3 -m json.tool
```

Se il primo comando funziona e il secondo no, il problema è nella
configurazione Apache.

------------------------------------------------------------------------

# 18.1 Installazione dei simboli APRS

Le mappe utilizzano i simboli APRS reali tramite la libreria
**OK-DMR/aprs-symbols**.

Il progetto originale è disponibile su GitHub:

``` text
https://github.com/OK-DMR/aprs-symbols
```

La libreria contiene:

-   `aprs-symbols.js`;
-   `aprs-symbols.css`;
-   sprite PNG APRS nelle dimensioni 24, 48, 64 e 128 pixel.

Tutti i file della libreria APRS devono essere installati in:

``` text
/var/www/html/lora-aprs/aprs-symbols/
```

Le pagine web del progetto caricano simboli e sprite direttamente da
questa directory.

Scaricare temporaneamente la libreria:

``` bash
git clone --depth 1 \
    https://github.com/OK-DMR/aprs-symbols.git \
    /tmp/aprs-symbols
```

Copiare JavaScript, CSS e sprite PNG:

``` bash
cp /tmp/aprs-symbols/aprs-symbols.js \
   /var/www/html/lora-aprs/aprs-symbols/

cp /tmp/aprs-symbols/aprs-symbols.css \
   /var/www/html/lora-aprs/aprs-symbols/

cp /tmp/aprs-symbols/aprs-symbols-*.png \
   /var/www/html/lora-aprs/aprs-symbols/
```

Impostare i permessi:

``` bash
chmod 755 /var/www/html/lora-aprs/aprs-symbols
chmod 644 /var/www/html/lora-aprs/aprs-symbols/*
```

Eliminare la copia temporanea:

``` bash
rm -rf /tmp/aprs-symbols
```

Verificare che Apache riesca a servire i file:

``` bash
curl -I http://127.0.0.1/aprs-symbols/aprs-symbols.js
```

e:

``` bash
curl -I http://127.0.0.1/aprs-symbols/aprs-symbols-24-0.png
```

Entrambi devono restituire:

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

# 19. Apertura della mappa

Da un browser:

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

# 20. Primo collaudo completo

Prima di considerare terminata l'installazione verificare nell'ordine:

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

# 21. Aggiunta successiva di un ricevitore

Modificare:

``` bash
nano /opt/lora-aprs/backend/config.py
```

Aggiungere il nuovo ricevitore a `RECEIVERS`.

Poi:

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

# 23. Aggiornamento futuro da GitHub

Prima di qualsiasi aggiornamento sarà comunque consigliato eseguire il
backup di:

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

Controllare i log del collector.

Se compare un messaggio che indica che un ricevitore MQTT non è ancora
identificato, verificare che il `callsign` configurato per RX1/RX2 sia
esattamente quello trasmesso dal beacon dell'iGate.

È utile verificare anche il file:

``` text
/opt/lora-aprs/data/receiver-map.json
```

che contiene le associazioni MQTT apprese automaticamente.

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

# 25. Sicurezza

Prima di pubblicare configurazioni o segnalare problemi su GitHub:

**NON pubblicare:**

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

# 26. Verifica finale dell'installazione

Il test finale sarà:

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

Progetto open source nato nello spirito della sperimentazione e della
condivisione radioamatoriale.

**73 de IV3SCP**
