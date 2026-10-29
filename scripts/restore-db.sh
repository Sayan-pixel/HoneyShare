#!/usr/bin/env bash
# HoneyShare Database Restore Drill Script (POSIX Bash)
# Complies with Specification Section 14
set -euo pipefail

BACKUP_FILE="${1:?Usage: $0 <path-to-backup.sql>}"
CONTAINER_NAME="${2:-honeyshare-mysql}"
DB_USER="${3:-root}"
DB_PASS="${4:-rootchangeme}"
DB_NAME="${5:-honeyshare}"
DB_PORT="${6:-3307}"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "ERROR: Backup file not found: $BACKUP_FILE" >&2
    exit 1
fi

echo "==> Starting HoneyShare database restore drill from: $BACKUP_FILE"

if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "==> Restoring into Docker container: $CONTAINER_NAME"
    docker exec -i "$CONTAINER_NAME" mysql -u "$DB_USER" "-p$DB_PASS" "$DB_NAME" < "$BACKUP_FILE"
    echo "==> Verifying table counts..."
    docker exec "$CONTAINER_NAME" mysql -u "$DB_USER" "-p$DB_PASS" \
        -e "SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='$DB_NAME';"
else
    echo "==> Restoring via local mysql client..."
    mysql -h 127.0.0.1 -P "$DB_PORT" -u "$DB_USER" "-p$DB_PASS" "$DB_NAME" < "$BACKUP_FILE"
    echo "==> Verifying table counts..."
    mysql -h 127.0.0.1 -P "$DB_PORT" -u "$DB_USER" "-p$DB_PASS" \
        -e "SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='$DB_NAME';"
fi

echo "==> Restore drill completed successfully!"

