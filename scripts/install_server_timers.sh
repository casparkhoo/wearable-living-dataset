#!/usr/bin/env bash
#
# Install the server's scheduled jobs as systemd timers (run once, on the server).
#
#   wearable-mqtt-sync   every minute: broker accounts for new participants
#                        (scripts/sync_mqtt_users.sh; does nothing if unchanged)
#   wearable-backup      nightly at 03:15 server time: database dump with rotation
#                        (scripts/backup_db.sh)
#
#   sudo APP_DIR=/home/ubuntu/wearable-app RUN_AS=ubuntu scripts/install_server_timers.sh
#
# Check them with:  systemctl list-timers 'wearable-*'
# Logs:             journalctl -u wearable-backup -u wearable-mqtt-sync

set -euo pipefail
APP_DIR="${APP_DIR:-/home/ubuntu/wearable-app}"
RUN_AS="${RUN_AS:-ubuntu}"
[ "$(id -u)" = 0 ] || { echo "run with sudo" >&2; exit 1; }

unit() { # name, description, script, OnCalendar
  cat > "/etc/systemd/system/$1.service" <<UNIT
[Unit]
Description=$2
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
User=$RUN_AS
Environment=APP_DIR=$APP_DIR
ExecStart=/bin/bash $APP_DIR/scripts/$3
UNIT
  cat > "/etc/systemd/system/$1.timer" <<UNIT
[Unit]
Description=$2 (schedule)

[Timer]
OnCalendar=$4
Persistent=true

[Install]
WantedBy=timers.target
UNIT
}

unit wearable-mqtt-sync "Broker accounts for new participants" sync_mqtt_users.sh "*-*-* *:*:00"
unit wearable-backup "Nightly living-dataset backup" backup_db.sh "*-*-* 03:15:00"

systemctl daemon-reload
systemctl enable --now wearable-mqtt-sync.timer wearable-backup.timer
systemctl list-timers 'wearable-*' --no-pager
