#!/usr/bin/env bash
#
# End-to-end check of participant erasure against a throwaway MySQL.
#
# Starts mysql:8.4 with backend/schema.sql, inserts two participants in one
# study (plus one recording of the first participant in another study), runs
# `wearable-backend erase-participant` as a dry run and then for real, and
# checks that exactly the first participant's rows in that study are gone,
# the other participant and the other study are untouched, and the erasure is
# logged. Needs Docker and a built backend (cargo build).
#
#   scripts/test_erasure.sh

set -euo pipefail
cd "$(dirname "$0")/.."

NAME=erase-test
PORT=3407
BIN=backend/target/debug/wearable-backend
[ -x "$BIN" ] || BIN="$BIN.exe"

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

SCHEMA="$(cd backend && pwd)/schema.sql"
if command -v cygpath >/dev/null; then SCHEMA="$(cygpath -m "$SCHEMA")"; fi
MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" -e MYSQL_ROOT_PASSWORD=testroot \
  -e MYSQL_DATABASE=wearable_living_dataset -p "127.0.0.1:$PORT:3306" \
  -v "$SCHEMA:/docker-entrypoint-initdb.d/01-schema.sql:ro" mysql:8.4 >/dev/null

sql() { docker exec -i "$NAME" mysql -uroot -ptestroot -N -B wearable_living_dataset "$@" 2>/dev/null; }

echo "== waiting for MySQL"
for _ in $(seq 1 60); do
  sql -e "SELECT 1 FROM erasure_log LIMIT 1" >/dev/null && break
  sleep 3
done

echo "== seeding"
sql <<'SQL'
INSERT INTO users (id, auth_provider, auth_subject, email, platform_role) VALUES
  (1, 'supabase', 'a', 'admin@example.org', 'researcher'),
  (2, 'supabase', 'b', 'p1@example.org', 'participant'),
  (3, 'supabase', 'c', 'p2@example.org', 'participant');
INSERT INTO studies (id, name, status, created_by_user_id) VALUES (10, 'Study A', 'active', 1), (11, 'Study B', 'active', 1);
INSERT INTO study_memberships (study_id, user_id, role, status) VALUES
  (10, 1, 'study_admin', 'active'), (10, 2, 'participant', 'active'), (10, 3, 'participant', 'active'), (11, 2, 'participant', 'active');
INSERT INTO devices (id, device_type, device_address) VALUES (1, 'OpenRing', 'AA');
INSERT INTO experiments (id, study_id, owner_user_id, experiment_label, status) VALUES
  (100, 10, 2, 'p1 in A', 'stopped'), (101, 10, 3, 'p2 in A', 'stopped'), (102, 11, 2, 'p1 in B', 'stopped');
INSERT INTO sessions (id, experiment_id, study_id, owner_user_id, device_id, status, started_at_ms) VALUES
  (200, 100, 10, 2, 1, 'stopped', 1), (201, 101, 10, 3, 1, 'stopped', 1), (202, 102, 11, 2, 1, 'stopped', 1);
INSERT INTO measurement_configs (id, session_id, device_id, command_name, started_at_ms) VALUES (300, 200, 1, 'start', 1), (301, 201, 1, 'start', 1);
INSERT INTO raw_packets (id, session_id, device_id, measurement_config_id, received_timestamp_ms, payload) VALUES
  (400, 200, 1, 300, 1, '{}'), (401, 201, 1, 301, 1, '{}'), (402, 202, 1, NULL, 1, '{}');
INSERT INTO stream_samples (session_id, device_id, raw_packet_id, stream_name, received_timestamp_ms, channel_values) VALUES
  (200, 1, 400, 'ppg', 1, '[1]'), (200, 1, 400, 'ppg', 2, '[2]'), (201, 1, 401, 'ppg', 1, '[1]'), (202, 1, 402, 'ppg', 1, '[1]');
INSERT INTO annotations (experiment_id, session_id, label_name, label_value, started_at_ms, server_received_at_ms) VALUES
  (100, 200, 'activity', 'rest', 1, 1), (101, 201, 'activity', 'rest', 1, 1);
INSERT INTO live_events (session_id, owner_user_id, event_type, window_start_ms, window_end_ms, detected_at_ms, recipe_version, details) VALUES
  (200, 2, 'alert', 1, 2, 2, 'x', '{}'), (201, 3, 'alert', 1, 2, 2, 'x', '{}');
INSERT INTO derived_runs (id, session_id, run_kind, methods, status, started_at_ms) VALUES (500, 200, 'recompute', '[]', 'completed', 1);
INSERT INTO derived_windows (session_id, run_id, method_id, method_version, window_start_ms, window_end_ms) VALUES
  (200, 500, 'live_ppg', '1', 1, 2), (201, NULL, 'live_ppg', '1', 1, 2);
SQL

export MYSQL_HOST=127.0.0.1 MYSQL_PORT=$PORT MYSQL_USER=root MYSQL_PASSWORD=testroot MYSQL_DATABASE=wearable_living_dataset

echo "== refuses without --confirm"
if "$BIN" erase-participant 2 --study 10 >/dev/null 2>&1; then echo "FAIL: erased without --confirm"; exit 1; fi

echo "== dry run"
"$BIN" erase-participant 2 --study 10 --dry-run
[ "$(sql -e 'SELECT COUNT(*) FROM stream_samples WHERE session_id = 200')" = 2 ] || { echo "FAIL: dry run deleted"; exit 1; }

echo "== erase"
"$BIN" erase-participant 2 --study 10 --confirm --reason "participant request (test)"

check() { # description, query, expected
  got=$(sql -e "$2")
  if [ "$got" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got $got, expected $3"; FAILED=1; fi
}
FAILED=0
check "p1's recording in study A is gone"        "SELECT COUNT(*) FROM sessions WHERE id = 200" 0
check "p1's samples in study A are gone"          "SELECT COUNT(*) FROM stream_samples WHERE session_id = 200" 0
check "p1's packets/configs/events/derived gone"  "SELECT (SELECT COUNT(*) FROM raw_packets WHERE session_id=200)+(SELECT COUNT(*) FROM measurement_configs WHERE session_id=200)+(SELECT COUNT(*) FROM live_events WHERE session_id=200)+(SELECT COUNT(*) FROM derived_windows WHERE session_id=200)+(SELECT COUNT(*) FROM derived_runs WHERE session_id=200)" 0
check "p1's experiment and labels in A are gone"  "SELECT (SELECT COUNT(*) FROM experiments WHERE id=100)+(SELECT COUNT(*) FROM annotations WHERE experiment_id=100)" 0
check "p2 in study A is untouched"                "SELECT (SELECT COUNT(*) FROM sessions WHERE id=201)+(SELECT COUNT(*) FROM stream_samples WHERE session_id=201)+(SELECT COUNT(*) FROM derived_windows WHERE session_id=201)" 3
check "p1 in study B is untouched"                "SELECT (SELECT COUNT(*) FROM sessions WHERE id=202)+(SELECT COUNT(*) FROM stream_samples WHERE session_id=202)" 2
check "p1's membership in A is withdrawn"         "SELECT status FROM study_memberships WHERE study_id=10 AND user_id=2" withdrawn
check "p1's membership in B is still active"      "SELECT status FROM study_memberships WHERE study_id=11 AND user_id=2" active
check "the erasure is logged as completed"        "SELECT CONCAT(status, ':', subject_user_id, ':', study_id) FROM erasure_log" completed:2:10

echo "== platform role from the command line"
"$BIN" set-platform-role p2@example.org researcher >/dev/null
check "set-platform-role grants researcher"       "SELECT platform_role FROM users WHERE id = 3" researcher

[ "$FAILED" = 0 ] && echo "ALL PASSED" || { echo "SOME CHECKS FAILED"; exit 1; }
