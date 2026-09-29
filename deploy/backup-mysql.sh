#!/usr/bin/env bash
# Daily logical backup of the mysql container, kept on the host for 14 days.
# Installed as a cron job on the server (see README "DB 백업").
set -euo pipefail
cd "$(dirname "$0")"

BACKUP_DIR="${BACKUP_DIR:-$HOME/mysql-backups}"
KEEP_DAYS=14
mkdir -p "$BACKUP_DIR"

stamp="$(date +%Y%m%d-%H%M%S)"
out="$BACKUP_DIR/lab_calendar-$stamp.sql.gz"

# The password is read inside the container so it never lands in the host's
# process list or this script.
docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers lab_calendar' \
  | gzip > "$out.tmp"
mv "$out.tmp" "$out"

find "$BACKUP_DIR" -name 'lab_calendar-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
echo "backup written: $out"
