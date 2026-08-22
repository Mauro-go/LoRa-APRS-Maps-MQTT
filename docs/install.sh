#!/usr/bin/env bash
set -Eeuo pipefail

# ============================================================
# LoRa APRS Maps MQTT - Interactive Installer
# English-only installer
# ============================================================

APP_DIR="/opt/lora-aprs"
BACKEND_DIR="${APP_DIR}/backend"
DATA_DIR="${APP_DIR}/data"
MAINTENANCE_DIR="${APP_DIR}/maintenance"
WEB_DIR="/var/www/html/lora-aprs"
SYMBOL_DIR="/var/www/html/aprs-symbols"

COLLECTOR_SERVICE="lora-aprs-collector.service"
API_SERVICE="lora-aprs-api.service"
DBMAINT_SERVICE="lora-aprs-dbmaintenance.service"
DBMAINT_TIMER="lora-aprs-dbmaintenance.timer"
APACHE_CONF_NAME="lora-aprs"

MQTT_TOPIC="lora_aprs/+/#"
API_HOST="127.0.0.1"
API_PORT="8080"
MAX_HISTORY_PACKETS="50000"

INSTALLER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_URL="https://github.com/Mauro-go/LoRa-APRS-Maps-MQTT.git"
REPO_BRANCH="main"
REPO_TMP="/tmp/LoRa-APRS-Maps-MQTT-install"
SOURCE_DIR=""
SERVER_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[[ -n "${SERVER_IP}" ]] || SERVER_IP="SERVER-IP"

BACKUP_STAMP="$(date +%Y%m%d-%H%M%S)"

cleanup() {
    rm -rf /tmp/lora-aprs-symbols-install 2>/dev/null || true
    rm -rf "${REPO_TMP}" 2>/dev/null || true
}
trap cleanup EXIT

die() {
    echo
    echo "ERROR: $*" >&2
    exit 1
}

yes_no() {
    local prompt="$1"
    local default="${2:-N}"
    local answer

    if [[ "${default}" == "Y" ]]; then
        read -r -p "${prompt} [Y/n]: " answer
        answer="${answer:-Y}"
    else
        read -r -p "${prompt} [y/N]: " answer
        answer="${answer:-N}"
    fi

    [[ "${answer,,}" == "y" || "${answer,,}" == "yes" ]]
}

ask_default() {
    local prompt="$1"
    local default="$2"
    local value
    read -r -p "${prompt} [${default}]: " value
    printf '%s' "${value:-$default}"
}

python_repr() {
    python3 -c 'import sys; print(repr(sys.stdin.read()))'
}

service_state() {
    local service="$1"
    if systemctl is-active --quiet "${service}"; then
        printf 'running'
    else
        printf 'NOT RUNNING'
    fi
}

echo
echo "============================================================"
echo " LoRa APRS Maps MQTT - Interactive Installer"
echo "============================================================"
echo

[[ "${EUID}" -eq 0 ]] || die "Run this installer as root."

if [[ -r /etc/os-release ]]; then
    . /etc/os-release
    echo "Detected system: ${PRETTY_NAME:-unknown}"
    if [[ "${ID:-}" != "debian" ]]; then
        echo "WARNING: This installer is designed for Debian 12/13."
        yes_no "Continue anyway?" "N" || exit 0
    fi
fi

echo
echo "Preparing the clean Debian system..."

echo "Installing bootstrap packages (CA certificates, Git and curl)..."
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ca-certificates \
    git \
    curl

echo
echo "Downloading LoRa APRS Maps MQTT from GitHub..."
rm -rf "${REPO_TMP}"

if ! git clone --depth 1 --branch "${REPO_BRANCH}" "${REPO_URL}" "${REPO_TMP}"; then
    die "Unable to download the project from GitHub."
fi

SOURCE_DIR="${REPO_TMP}"

echo
echo "Checking downloaded repository files..."

required_files=(
    "backend/collector.py"
    "backend/api.py"
    "backend/config.example.py"
    "web/index.html"
    "web/station.html"
    "web/dashboard.html"
    "web/coverage.html"
    "systemd/lora-aprs-collector.service"
    "systemd/lora-aprs-api.service"
    "maintenance/dbmaintenance.sh"
    "systemd/lora-aprs-dbmaintenance.service"
    "systemd/lora-aprs-dbmaintenance.timer"
    "apache/lora-aprs.conf"
)

for item in "${required_files[@]}"; do
    [[ -e "${SOURCE_DIR}/${item}" ]] || die "Required file not found in the GitHub repository: ${item}"
done

echo "GitHub repository: OK"
echo "Source: ${REPO_URL}"

# ============================================================
# MQTT configuration
# ============================================================

echo
echo "---------------- MQTT configuration ----------------"

INSTALL_LOCAL_MQTT="no"
LOCAL_MQTT_AUTH="no"
MQTT_USER=""
MQTT_PASSWORD=""

if yes_no "Install a local Mosquitto MQTT broker on this server?" "N"; then
    INSTALL_LOCAL_MQTT="yes"
    MQTT_HOST="127.0.0.1"
    MQTT_PORT="1883"

    echo
    echo "The LoRa iGates will connect to this server at:"
    echo "  ${SERVER_IP}:1883"
    echo

    if yes_no "Require username/password for the local MQTT broker?" "N"; then
        LOCAL_MQTT_AUTH="yes"

        while [[ -z "${MQTT_USER}" ]]; do
            read -r -p "MQTT username: " MQTT_USER
        done

        while [[ -z "${MQTT_PASSWORD}" ]]; do
            read -r -s -p "MQTT password: " MQTT_PASSWORD
            echo
            [[ -n "${MQTT_PASSWORD}" ]] || echo "Password cannot be empty."
        done
    fi
else
    MQTT_HOST=""
    while [[ -z "${MQTT_HOST}" ]]; do
        read -r -p "MQTT broker address or hostname: " MQTT_HOST
    done

    MQTT_PORT="$(ask_default "MQTT broker port" "1883")"

    read -r -p "MQTT username [leave blank if unused]: " MQTT_USER
    if [[ -n "${MQTT_USER}" ]]; then
        read -r -s -p "MQTT password: " MQTT_PASSWORD
        echo
    fi
fi

[[ "${MQTT_PORT}" =~ ^[0-9]+$ ]] || die "MQTT port must be numeric."
(( MQTT_PORT >= 1 && MQTT_PORT <= 65535 )) || die "MQTT port must be between 1 and 65535."

# ============================================================
# Receiver configuration
# ============================================================

echo
echo "---------------- Receiver configuration ----------------"
echo
echo "The current backend derives the MQTT receiver name from the APRS callsign."
echo "Example: IV3XXX-10 -> MQTT receiver name IV3XXX"
echo

RECEIVER_COUNT=""
while :; do
    RECEIVER_COUNT="$(ask_default "How many LoRa APRS receivers do you want to configure?" "1")"
    if [[ "${RECEIVER_COUNT}" =~ ^[0-9]+$ ]] && (( RECEIVER_COUNT >= 1 && RECEIVER_COUNT <= 50 )); then
        break
    fi
    echo "Enter a number between 1 and 50."
done

declare -a RECEIVER_CALLSIGNS=()
declare -a RECEIVER_MQTT_NAMES=()

for ((i=1; i<=RECEIVER_COUNT; i++)); do
    echo
    echo "Receiver ${i}"

    callsign=""
    while [[ -z "${callsign}" ]]; do
        read -r -p "APRS callsign (including SSID when used): " callsign
        callsign="${callsign^^}"
        callsign="${callsign// /}"
        [[ -n "${callsign}" ]] || echo "Callsign cannot be empty."
    done

    mqtt_name="${callsign%%-*}"

    RECEIVER_CALLSIGNS+=("${callsign}")
    RECEIVER_MQTT_NAMES+=("${mqtt_name}")

    echo "MQTT receiver name: ${mqtt_name}"
    echo "Expected topic: lora_aprs/${mqtt_name}/..."
done

# ============================================================
# Summary
# ============================================================

echo
echo "============================================================"
echo " Configuration summary"
echo "============================================================"

if [[ "${INSTALL_LOCAL_MQTT}" == "yes" ]]; then
    echo "MQTT broker        : local Mosquitto"
    echo "Collector connects : 127.0.0.1:1883"
    echo "iGate broker       : ${SERVER_IP}:1883"
    if [[ "${LOCAL_MQTT_AUTH}" == "yes" ]]; then
        echo "MQTT authentication: enabled"
        echo "MQTT username      : ${MQTT_USER}"
    else
        echo "MQTT authentication: disabled"
    fi
else
    echo "MQTT broker        : ${MQTT_HOST}:${MQTT_PORT}"
    if [[ -n "${MQTT_USER}" ]]; then
        echo "MQTT authentication: enabled"
        echo "MQTT username      : ${MQTT_USER}"
    else
        echo "MQTT authentication: disabled"
    fi
fi

echo "MQTT topic         : ${MQTT_TOPIC}"
echo "Receivers          : ${RECEIVER_COUNT}"

for ((i=0; i<RECEIVER_COUNT; i++)); do
    printf '  RX%-2d %-15s -> MQTT name %s\n' \
        "$((i+1))" "${RECEIVER_CALLSIGNS[$i]}" "${RECEIVER_MQTT_NAMES[$i]}"
done

echo "Web address        : http://${SERVER_IP}/lora-aprs/"
echo "API proxy          : http://${SERVER_IP}/lora-api/status"
echo "============================================================"
echo

yes_no "Start installation?" "Y" || {
    echo "Installation cancelled."
    exit 0
}

# ============================================================
# Packages
# ============================================================

echo
echo "[1/10] Installing required Debian packages..."

apt-get update

packages=(
    ca-certificates
    python3
    python3-paho-mqtt
    sqlite3
    apache2
    mosquitto-clients
    git
    curl
)

if [[ "${INSTALL_LOCAL_MQTT}" == "yes" ]]; then
    packages+=(mosquitto)
fi

DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"

# ============================================================
# Application directories and backend
# ============================================================

echo
echo "[2/10] Installing backend files..."

mkdir -p "${BACKEND_DIR}" "${DATA_DIR}"

cp -a "${SOURCE_DIR}/backend/." "${BACKEND_DIR}/"

chmod 755 "${BACKEND_DIR}/collector.py"
chmod 755 "${BACKEND_DIR}/api.py"
chmod 755 "${APP_DIR}" "${BACKEND_DIR}" "${DATA_DIR}"

if [[ -f "${BACKEND_DIR}/config.py" ]]; then
    cp -a "${BACKEND_DIR}/config.py" \
        "${BACKEND_DIR}/config.py.bak-${BACKUP_STAMP}"
    echo "Existing config.py backed up."
fi

if [[ -f "${DATA_DIR}/lora-aprs.db" ]]; then
    echo "Existing SQLite database found: it will be preserved."
fi

# ============================================================
# Generate config.py
# ============================================================

echo
echo "[3/10] Creating backend configuration..."

MQTT_HOST_PY="$(printf '%s' "${MQTT_HOST}" | python_repr)"
MQTT_USER_PY="$(printf '%s' "${MQTT_USER}" | python_repr)"
MQTT_PASSWORD_PY="$(printf '%s' "${MQTT_PASSWORD}" | python_repr)"
MQTT_TOPIC_PY="$(printf '%s' "${MQTT_TOPIC}" | python_repr)"

cat > "${BACKEND_DIR}/config.py" <<EOF
#!/usr/bin/env python3

# Generated by the LoRa APRS Maps MQTT interactive installer.

BASE_DIR = "/opt/lora-aprs"
DATA_DIR = f"{BASE_DIR}/data"
DB_FILE = f"{DATA_DIR}/lora-aprs.db"
LOG_FILE = f"{DATA_DIR}/packets.log"

MQTT_HOST = ${MQTT_HOST_PY}
MQTT_PORT = ${MQTT_PORT}
MQTT_USER = ${MQTT_USER_PY}
MQTT_PASSWORD = ${MQTT_PASSWORD_PY}
MQTT_TOPIC = ${MQTT_TOPIC_PY}

API_HOST = "${API_HOST}"
API_PORT = ${API_PORT}
MAX_HISTORY_PACKETS = ${MAX_HISTORY_PACKETS}

RECEIVERS = {
EOF

for ((i=0; i<RECEIVER_COUNT; i++)); do
    call_py="$(printf '%s' "${RECEIVER_CALLSIGNS[$i]}" | python_repr)"
    cat >> "${BACKEND_DIR}/config.py" <<EOF
    "RX$((i+1))": {
        "callsign": ${call_py},
        "enabled": True,
    },
EOF
done

cat >> "${BACKEND_DIR}/config.py" <<'EOF'
}
EOF

chmod 600 "${BACKEND_DIR}/config.py"

python3 -m py_compile "${BACKEND_DIR}/collector.py"
python3 -m py_compile "${BACKEND_DIR}/api.py"
python3 -m py_compile "${BACKEND_DIR}/config.py"

# ============================================================
# Local Mosquitto, if requested
# ============================================================

echo
echo "[4/10] Configuring MQTT broker..."

if [[ "${INSTALL_LOCAL_MQTT}" == "yes" ]]; then
    mkdir -p /etc/mosquitto/conf.d

    if [[ "${LOCAL_MQTT_AUTH}" == "yes" ]]; then
        mosquitto_passwd -b -c /etc/mosquitto/lora-aprs.passwd \
            "${MQTT_USER}" "${MQTT_PASSWORD}"

        # Mosquitto drops privileges and runs as the "mosquitto" user.
        # The password file must therefore be readable by that account.
        chown root:mosquitto /etc/mosquitto/lora-aprs.passwd
        chmod 640 /etc/mosquitto/lora-aprs.passwd

        cat > /etc/mosquitto/conf.d/lora-aprs.conf <<'EOF'
listener 1883 0.0.0.0
allow_anonymous false
password_file /etc/mosquitto/lora-aprs.passwd
EOF
    else
        cat > /etc/mosquitto/conf.d/lora-aprs.conf <<'EOF'
listener 1883 0.0.0.0
allow_anonymous true
EOF
    fi

    systemctl enable mosquitto
    systemctl restart mosquitto
    systemctl is-active --quiet mosquitto || die "Mosquitto failed to start."
else
    echo "Using external MQTT broker: ${MQTT_HOST}:${MQTT_PORT}"
fi

# ============================================================
# Web frontend
# ============================================================

echo
echo "[5/10] Installing web interface..."

mkdir -p "${WEB_DIR}"
cp -a "${SOURCE_DIR}/web/." "${WEB_DIR}/"

find "${WEB_DIR}" -type d -exec chmod 755 {} \;
find "${WEB_DIR}" -type f -exec chmod 644 {} \;

# ============================================================
# APRS symbols
# ============================================================

echo
echo "[6/10] Installing APRS symbol library..."

rm -rf /tmp/lora-aprs-symbols-install
git clone --depth 1 \
    https://github.com/OK-DMR/aprs-symbols.git \
    /tmp/lora-aprs-symbols-install

mkdir -p "${SYMBOL_DIR}"

install -m 644 \
    /tmp/lora-aprs-symbols-install/aprs-symbols.js \
    "${SYMBOL_DIR}/aprs-symbols.js"

install -m 644 \
    /tmp/lora-aprs-symbols-install/aprs-symbols.css \
    "${SYMBOL_DIR}/aprs-symbols.css"

shopt -s nullglob
symbol_pngs=(/tmp/lora-aprs-symbols-install/aprs-symbols-*.png)
(( ${#symbol_pngs[@]} > 0 )) || die "APRS symbol PNG files were not found."
install -m 644 "${symbol_pngs[@]}" "${SYMBOL_DIR}/"
shopt -u nullglob

chmod 755 "${SYMBOL_DIR}"

# ============================================================
# Database maintenance
# ============================================================

echo
echo "[7/10] Installing database maintenance..."

mkdir -p "${MAINTENANCE_DIR}"

install -m 750 \
    "${SOURCE_DIR}/maintenance/dbmaintenance.sh" \
    "${MAINTENANCE_DIR}/dbmaintenance.sh"

chmod 755 "${MAINTENANCE_DIR}"

# ============================================================
# systemd services
# ============================================================

echo
echo "[8/10] Installing systemd services..."

install -m 644 \
    "${SOURCE_DIR}/systemd/lora-aprs-collector.service" \
    "/etc/systemd/system/${COLLECTOR_SERVICE}"

install -m 644 \
    "${SOURCE_DIR}/systemd/lora-aprs-api.service" \
    "/etc/systemd/system/${API_SERVICE}"

install -m 644 \
    "${SOURCE_DIR}/systemd/lora-aprs-dbmaintenance.service" \
    "/etc/systemd/system/${DBMAINT_SERVICE}"

install -m 644 \
    "${SOURCE_DIR}/systemd/lora-aprs-dbmaintenance.timer" \
    "/etc/systemd/system/${DBMAINT_TIMER}"

systemctl daemon-reload

systemctl enable "${COLLECTOR_SERVICE}" "${API_SERVICE}"
systemctl enable --now "${DBMAINT_TIMER}"

systemctl restart "${COLLECTOR_SERVICE}"
systemctl restart "${API_SERVICE}"

# Give the API a moment to bind.
for _ in {1..10}; do
    if curl -fsS "http://127.0.0.1:${API_PORT}/api/status" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

# ============================================================
# Apache
# ============================================================

echo
echo "[9/10] Configuring Apache..."

a2enmod proxy >/dev/null
a2enmod proxy_http >/dev/null

install -m 644 \
    "${SOURCE_DIR}/apache/lora-aprs.conf" \
    "/etc/apache2/conf-available/${APACHE_CONF_NAME}.conf"

a2enconf "${APACHE_CONF_NAME}" >/dev/null

apache2ctl configtest
systemctl reload apache2

# ============================================================
# Final checks
# ============================================================

echo
echo "[10/10] Running final checks..."

collector_ok="no"
api_ok="no"
db_timer_ok="no"
proxy_ok="no"
symbol_ok="no"
web_ok="no"

systemctl is-active --quiet "${COLLECTOR_SERVICE}" && collector_ok="yes"
systemctl is-active --quiet "${API_SERVICE}" && api_ok="yes"
systemctl is-active --quiet "${DBMAINT_TIMER}" && db_timer_ok="yes"

curl -fsS "http://127.0.0.1:${API_PORT}/api/status" >/dev/null 2>&1 \
    && api_ok="yes"

curl -fsS "http://127.0.0.1/lora-api/status" >/dev/null 2>&1 \
    && proxy_ok="yes"

curl -fsSI "http://127.0.0.1/aprs-symbols/aprs-symbols.js" >/dev/null 2>&1 \
    && symbol_ok="yes"

curl -fsSI "http://127.0.0.1/lora-aprs/" >/dev/null 2>&1 \
    && web_ok="yes"

echo
echo "============================================================"
echo " Installation completed"
echo "============================================================"
echo "Map      : http://${SERVER_IP}/lora-aprs/"
echo "Dashboard: http://${SERVER_IP}/lora-aprs/dashboard.html"
echo "API      : http://${SERVER_IP}/lora-api/status"
echo "Source   : ${REPO_URL}"
echo
echo "Services:"
printf '  %-32s %s\n' "${COLLECTOR_SERVICE}" "$(service_state "${COLLECTOR_SERVICE}")"
printf '  %-32s %s\n' "${API_SERVICE}" "$(service_state "${API_SERVICE}")"
printf '  %-32s %s\n' "${DBMAINT_TIMER}" "$(service_state "${DBMAINT_TIMER}")"
if [[ "${INSTALL_LOCAL_MQTT}" == "yes" ]]; then
    printf '  %-32s %s\n' "mosquitto.service" "$(service_state mosquitto.service)"
fi
echo
echo "Checks:"
printf '  %-22s %s\n' "API direct" "${api_ok}"
printf '  %-22s %s\n' "Database timer" "${db_timer_ok}"
printf '  %-22s %s\n' "Apache API proxy" "${proxy_ok}"
printf '  %-22s %s\n' "APRS symbols" "${symbol_ok}"
printf '  %-22s %s\n' "Web interface" "${web_ok}"
echo
echo "Configured receivers:"
for ((i=0; i<RECEIVER_COUNT; i++)); do
    printf '  %-15s MQTT receiver name: %s\n' \
        "${RECEIVER_CALLSIGNS[$i]}" "${RECEIVER_MQTT_NAMES[$i]}"
done

if [[ "${INSTALL_LOCAL_MQTT}" == "yes" ]]; then
    echo
    echo "Configure each LoRa APRS iGate to use MQTT broker:"
    echo "  Host: ${SERVER_IP}"
    echo "  Port: 1883"
    if [[ "${LOCAL_MQTT_AUTH}" == "yes" ]]; then
        echo "  Username: ${MQTT_USER}"
        echo "  Password: the password entered during installation"
    else
        echo "  Authentication: none"
    fi
fi

echo
echo "Private configuration:"
echo "  ${BACKEND_DIR}/config.py"
echo
echo "IMPORTANT: Do not publish config.py if it contains MQTT credentials."
echo "============================================================"

if [[ "${collector_ok}" != "yes" || "${api_ok}" != "yes" || \
      "${db_timer_ok}" != "yes" || "${proxy_ok}" != "yes" || \
      "${symbol_ok}" != "yes" || "${web_ok}" != "yes" ]]; then
    echo
    echo "One or more checks did not pass."
    echo "Useful diagnostics:"
    echo "  journalctl -u ${COLLECTOR_SERVICE} -n 100 --no-pager"
    echo "  journalctl -u ${API_SERVICE} -n 100 --no-pager"
    echo "  systemctl status ${DBMAINT_TIMER} --no-pager"
    echo "  journalctl -u ${DBMAINT_SERVICE} -n 100 --no-pager"
    echo "  apache2ctl configtest"
    exit 2
fi

echo
echo "All installation checks passed."
