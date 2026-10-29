#!/usr/bin/env bash
# HoneyShare Database Backup Script (POSIX Bash)
# Complies with Specification Section 14
set -euo pipefail

OUTPUT_DIR="${1:-./backups}"
CONTAINER_NAME="${2:-honeyshare-mysql}"
DB_USER="${3:-root}"
DB_PASS="${4:-rootchangeme}"
DB_NAME="${5:-honeyshare}"
DB_PORT="${6:-3307}"

mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
BACKUP_FILE="$OUTPUT_DIR/honeyshare-backup-$TIMESTAMP.sql"

echo "==> Starting HoneyShare database backup..."

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "==> Dumping from Docker container: $CONTAINER_NAME"
    docker exec "$CONTAINER_NAME" mysqldump -u "$DB_USER" "-p$DB_PASS" \
        --single-transaction --quick --routines --triggers "$DB_NAME" > "$BACKUP_FILE"
else
    echo "==> Dumping via local mysqldump binary..."
    mysqldump -h 127.0.0.1 -P "$DB_PORT" -u "$DB_USER" "-p$DB_PASS" \
        --single-transaction --quick --routines --triggers "$DB_NAME" > "$BACKUP_FILE"
fi

FILE_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "==> Backup completed successfully: $BACKUP_FILE ($FILE_SIZE)"

