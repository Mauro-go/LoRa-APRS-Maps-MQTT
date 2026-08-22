#!/bin/bash

set -euo pipefail

DB="/opt/lora-aprs/data/lora-aprs.db"
BACKUP_DIR="/opt/lora-aprs/db-backups"

RETENTION_DAYS=7
BACKUP_KEEP_DAYS=90

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DB="$BACKUP_DIR/lora-aprs-$STAMP.db"

echo
echo "======================================================"
echo " LoRa APRS Maps MQTT - DATABASE MAINTENANCE"
echo "======================================================"
echo

if [ ! -f "$DB" ]; then
    echo "ERRORE: database non trovato:"
    echo "$DB"
    exit 1
fi

mkdir -p "$BACKUP_DIR"

echo "===== STATO PRIMA ====="

BEFORE_COUNT="$(sqlite3 "$DB" "SELECT COUNT(*) FROM packets;")"

OLDEST="$(sqlite3 "$DB" "
SELECT COALESCE(MIN(timestamp),'N/A')
FROM packets;
")"

NEWEST="$(sqlite3 "$DB" "
SELECT COALESCE(MAX(timestamp),'N/A')
FROM packets;
")"

echo "Pacchetti   : $BEFORE_COUNT"
echo "Più vecchio : $OLDEST"
echo "Più recente : $NEWEST"

echo
echo "===== BACKUP DATABASE ====="

sqlite3 "$DB" ".backup '$BACKUP_DB'"

gzip "$BACKUP_DB"

echo "OK - backup creato:"
echo "$BACKUP_DB.gz"

echo
echo "===== PULIZIA RECORD > ${RETENTION_DAYS} GIORNI ====="

sqlite3 "$DB" "
DELETE FROM packets
WHERE datetime(timestamp) < datetime('now','-${RETENTION_DAYS} days');
"

AFTER_COUNT="$(sqlite3 "$DB" "SELECT COUNT(*) FROM packets;")"

DELETED=$(( BEFORE_COUNT - AFTER_COUNT ))

echo "Eliminati : $DELETED"
echo "Rimasti   : $AFTER_COUNT"

echo
echo "===== INTEGRITY CHECK ====="

CHECK="$(sqlite3 "$DB" "PRAGMA integrity_check;")"

echo "$CHECK"

if [ "$CHECK" != "ok" ]; then
    echo "ERRORE: integrity_check fallito"
    exit 1
fi

echo
echo "===== PULIZIA BACKUP > ${BACKUP_KEEP_DAYS} GIORNI ====="

find "$BACKUP_DIR" \
    -type f \
    -name 'lora-aprs-*.db.gz' \
    -mtime +"$BACKUP_KEEP_DAYS" \
    -print \
    -delete

echo
echo "======================================================"
echo " MANUTENZIONE COMPLETATA"
echo " Retention DB : ${RETENTION_DAYS} giorni"
echo " Backup       : $BACKUP_DB.gz"
echo "======================================================"
echo
