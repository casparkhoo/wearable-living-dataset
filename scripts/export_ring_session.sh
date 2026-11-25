#!/usr/bin/env bash
# Export one ring session from the cloud database as the CSV that
# align_reference.py reads: received_ms, green, ir, ax, ay, az.
#
#   SSH_KEY=~/path/to/key SSH_HOST=ubuntu@<vm> scripts/export_ring_session.sh 16 > ring_16.csv
#
# The key and host come from the environment so that neither is recorded here.
#
# The accelerometer is joined on the packet the PPG sample came in, not on the
# sample index alone: the phone's index restarts when the ring reconnects, so
# one index recurs within a recording and an index-only join matched every
# recurrence (session 66: 54,205 PPG rows but 40,705 distinct indices).
# For a full, checksummed export use GET /api/device-recordings/:id/export.
set -euo pipefail
session="${1:?session id}"
: "${SSH_KEY:?set SSH_KEY}" "${SSH_HOST:?set SSH_HOST}"

ssh -i "$SSH_KEY" "$SSH_HOST" "cd ~/wearable-app/deployment/cloud && docker compose exec -T mysql sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" -N -B -e \"
select p.received_timestamp_ms,
       json_extract(p.channel_values,\\\"\$.green\\\"), json_extract(p.channel_values,\\\"\$.ir\\\"),
       json_extract(a.channel_values,\\\"\$.x\\\"), json_extract(a.channel_values,\\\"\$.y\\\"), json_extract(a.channel_values,\\\"\$.z\\\")
from stream_samples p
left join stream_samples a on a.raw_packet_id=p.raw_packet_id and a.stream_name=\\\"accel\\\" and a.sample_index<=>p.sample_index
where p.session_id=${session} and p.stream_name=\\\"ppg\\\" order by p.id\" 2>/dev/null'" | tr '\t' ','
