#!/usr/bin/env bash
# Demo against the two nodes started by start-two-nodes.sh:
# creates one fixed-interval schedule on node 1, then shows on node 2 that
# instances were executed exactly once, by whichever node won the lease.
set -euo pipefail

BASE1=http://localhost:8081
BASE2=http://localhost:8082

echo "== creating schedule on node-1 (every 3 seconds) =="
ID=$(curl -sf -X POST "$BASE1/api/v1/schedules" \
  -H 'Content-Type: application/json' \
  -d '{"name":"cluster-demo","type":"FIXED_INTERVAL","intervalSeconds":3}' | jq -r .id)
echo "schedule id: $ID"

echo "== waiting 20s while both nodes plan/lease/execute =="
sleep 20

FROM=$(date -u -d '1 minute ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-1M +%Y-%m-%dT%H:%M:%SZ)
TO=$(date -u -d '2 minutes' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+2M +%Y-%m-%dT%H:%M:%SZ)

echo "== instances as seen from node-2 (status / utc / lease owner / fencing token) =="
curl -sf "$BASE2/api/v1/schedules/$ID/instances?from=$FROM&to=$TO" | jq -r '
  .[] | [.status, .scheduledAtUtc, (.lease.ownerNode // "-"), (.lease.fencingToken // "-" | tostring)] | @tsv'

echo
echo "== integrity check: one lease per instance, no active lease on finished instances =="
curl -sf "$BASE2/api/v1/schedules/$ID/instances?from=$FROM&to=$TO" | jq -r '
  "instances listed: \(length)",
  "instances with a lease: \([.[] | select(.lease != null)] | length)",
  "succeeded with active lease: \([.[] | select(.status == "SUCCEEDED" and .lease != null and .lease.status == "ACTIVE")] | length)"'
