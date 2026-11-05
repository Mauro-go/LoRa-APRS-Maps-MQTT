#!/bin/bash
set -euo pipefail

DB="/opt/lora-aprs/data/lora-aprs.db"
RETENTION_DAYS=7

echo "===== LORA APRS - MANUTENZIONE ====="
date

if [ ! -s "$DB" ]; then
    echo "ERRORE: database assente o vuoto"
    exit 1
fi

python3 - "$DB" "$RETENTION_DAYS" <<'PY'
import sqlite3
import sys
import time
from datetime import datetime, timedelta, timezone

db = sys.argv[1]
days = int(sys.argv[2])
cutoff = (datetime.now(timezone.utc) -
          timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")

conn = sqlite3.connect(db, timeout=30)
conn.execute("PRAGMA busy_timeout=30000")

try:
    before = conn.execute(
        "SELECT COUNT(*) FROM packets"
    ).fetchone()[0]

    print(f"Pacchetti prima: {before}", flush=True)

    total = 0

    while True:
        try:
            conn.execute("BEGIN IMMEDIATE")

            cur = conn.execute("""
                DELETE FROM packets
                WHERE id IN (
                    SELECT id FROM packets
                    WHERE datetime(timestamp) < ?
                    LIMIT 1000
                )
            """, (cutoff,))

            deleted = cur.rowcount
            conn.commit()

            total += deleted

            if deleted == 0:
                break

            time.sleep(0.1)

        except sqlite3.OperationalError as e:
            conn.rollback()
            print(f"Errore SQLite: {e}", flush=True)
            raise

    after = conn.execute(
        "SELECT COUNT(*) FROM packets"
    ).fetchone()[0]

    print(f"Pacchetti eliminati: {total}")
    print(f"Pacchetti rimasti: {after}")
    print("Manutenzione completata")

finally:
    conn.close()
PY
