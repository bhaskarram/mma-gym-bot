#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# MMA Gym Bot — MySQL Backup Script
# Runs daily via cron. Creates a compressed dump, retains 30 days, logs results.
#
# Setup (production server):
#   1. Copy this file to /opt/mmabot/backup.sh
#   2. chmod +x /opt/mmabot/backup.sh
#   3. Create backup dir: mkdir -p /backups/mmagym && chown ubuntu:ubuntu /backups/mmagym
#   4. Add to crontab (crontab -e):
#        0 2 * * * /opt/mmabot/backup.sh >> /var/log/mmabot-backup.log 2>&1
#
# Environment variables required (same as .env):
#   SPRING_DATASOURCE_USERNAME
#   SPRING_DATASOURCE_PASSWORD
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
DB_NAME="mmagym"
DB_USER="${SPRING_DATASOURCE_USERNAME:-mmagym_user}"
DB_PASS="${SPRING_DATASOURCE_PASSWORD}"
BACKUP_DIR="${BACKUP_DIR:-/backups/mmagym}"
RETAIN_DAYS=30
MIN_FREE_MB=500       # Abort if disk has less than this free
TIMESTAMP=$(date +"%Y-%m-%d_%H-%M-%S")
BACKUP_FILE="${BACKUP_DIR}/mmagym_${TIMESTAMP}.sql.gz"
LOG_PREFIX="[$(date '+%Y-%m-%d %H:%M:%S')] [backup]"

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { echo "${LOG_PREFIX} $*"; }
fail() { echo "${LOG_PREFIX} ERROR: $*" >&2; exit 1; }

# ── Pre-flight checks ─────────────────────────────────────────────────────────
log "Starting backup → ${BACKUP_FILE}"

# Ensure backup directory exists
mkdir -p "${BACKUP_DIR}" || fail "Cannot create backup directory: ${BACKUP_DIR}"

# Check available disk space
FREE_MB=$(df -m "${BACKUP_DIR}" | awk 'NR==2 {print $4}')
if [ "${FREE_MB}" -lt "${MIN_FREE_MB}" ]; then
    fail "Low disk space: ${FREE_MB}MB free (need ${MIN_FREE_MB}MB). Aborting backup."
fi
log "Disk space OK: ${FREE_MB}MB free"

# ── Run dump ──────────────────────────────────────────────────────────────────
# Flags explained:
#   --skip-opt          : disables FLUSH TABLES (avoids needing RELOAD privilege)
#   --create-options    : preserves ENGINE=InnoDB, CHARSET etc on CREATE TABLE
#   --add-drop-table    : DROP TABLE IF EXISTS before CREATE — safe for restore
#   --extended-insert   : multi-row INSERTs — faster restore
#   --disable-keys      : faster restore on MyISAM (no-op for InnoDB but harmless)
#   --set-charset       : includes SET NAMES utf8mb4 in dump
#   --no-tablespaces    : avoids needing PROCESS privilege (MySQL 8+/9+)
#   --set-gtid-purged=OFF : skip GTID headers — not needed for a single-DB dump
#   --routines, --triggers : include stored routines and triggers
#
# Production note: for the most consistent dump with zero table locking,
# grant RELOAD to backup user then switch back to --single-transaction:
#   GRANT RELOAD, PROCESS ON *.* TO 'mmagym_user'@'localhost';
MYSQL_PWD="${DB_PASS}" mysqldump \
    --user="${DB_USER}" \
    --skip-opt \
    --create-options \
    --add-drop-table \
    --extended-insert \
    --disable-keys \
    --set-charset \
    --no-tablespaces \
    --set-gtid-purged=OFF \
    --routines \
    --triggers \
    "${DB_NAME}" \
    | gzip -9 > "${BACKUP_FILE}"

# Verify the file was created and is non-empty
if [ ! -s "${BACKUP_FILE}" ]; then
    fail "Backup file is empty or missing: ${BACKUP_FILE}"
fi

SIZE=$(du -h "${BACKUP_FILE}" | cut -f1)
log "Backup complete: ${BACKUP_FILE} (${SIZE})"

# ── Retention — delete dumps older than RETAIN_DAYS ──────────────────────────
DELETED=$(find "${BACKUP_DIR}" -name "mmagym_*.sql.gz" -mtime "+${RETAIN_DAYS}" -print -delete | wc -l | tr -d ' ')
if [ "${DELETED}" -gt 0 ]; then
    log "Pruned ${DELETED} backup(s) older than ${RETAIN_DAYS} days"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
TOTAL=$(find "${BACKUP_DIR}" -name "mmagym_*.sql.gz" | wc -l | tr -d ' ')
log "Done. ${TOTAL} backup(s) on disk."
