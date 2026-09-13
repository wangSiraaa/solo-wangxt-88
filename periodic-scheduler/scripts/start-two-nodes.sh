#!/usr/bin/env bash
# Starts TWO scheduler processes against ONE shared database.
#
#   DATABASE_URL=jdbc:postgresql://localhost:5432/scheduler ./scripts/start-two-nodes.sh
#   ./scripts/start-two-nodes.sh          # demo mode: shared H2 over TCP (no Postgres needed)
#
# Node 1 listens on :8081, node 2 on :8082. Both run planner + dispatcher ticks and
# compete for the same trigger instances; the database enforces a single valid lease
# per instance. Stop with Ctrl-C (kills both nodes and the demo H2 server).
set -euo pipefail
cd "$(dirname "$0")/.."

JAR=target/periodic-scheduler-1.0.0.jar
if [ ! -f "$JAR" ]; then
  echo "building $JAR ..."
  mvn -q -DskipTests package
fi

PIDS=()
cleanup() { kill "${PIDS[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

if [ -n "${DATABASE_URL:-}" ]; then
  DB_URL="$DATABASE_URL"
  DB_DRIVER="${DATABASE_DRIVER:-org.postgresql.Driver}"
  DB_USER="${DATABASE_USER:-scheduler}"
  DB_PASS="${DATABASE_PASSWORD:?CHANGE_ME}"
  echo "using database: $DB_URL"
else
  echo "DATABASE_URL not set -> demo mode: starting a shared H2 TCP server"
  H2_JAR=$(find ~/.m2/repository/com/h2database/h2 -name 'h2-*.jar' 2>/dev/null | sort | tail -1)
  if [ -z "$H2_JAR" ]; then
    echo "H2 jar not found in ~/.m2; run 'mvn -q -DskipTests package' first" >&2
    exit 1
  fi
  java -cp "$H2_JAR" org.h2.tools.Server -tcp -tcpPort 9092 -ifNotExists &
  PIDS+=($!)
  sleep 1
  DB_URL="jdbc:h2:tcp://localhost:9092//tmp/scheduler-cluster-demo"
  DB_DRIVER="org.h2.Driver"
  DB_USER="sa"
  DB_PASS=""
  echo "using database: $DB_URL"
fi

start_node() {
  local node_id="$1" port="$2"
  APP_NODE_ID="$node_id" \
  SERVER_PORT="$port" \
  DATABASE_URL="$DB_URL" \
  DATABASE_DRIVER="$DB_DRIVER" \
  DATABASE_USER="$DB_USER" \
  DATABASE_PASSWORD="$DB_PASS" \
    java -jar "$JAR" > "/tmp/scheduler-${node_id}.log" 2>&1 &
  PIDS+=($!)
  echo "node $node_id starting (pid $!, port $port, log /tmp/scheduler-${node_id}.log)"
}

wait_for_node() {
  local port="$1"
  for _ in $(seq 1 60); do
    if curl -sf "http://localhost:${port}/api/v1/schedules" > /dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "node on port ${port} did not start in time; check the log" >&2
  return 1
}

# Node 1 first, so Flyway migrations run before node 2 joins.
start_node node-1 8081
wait_for_node 8081
start_node node-2 8082
wait_for_node 8082

echo
echo "both nodes are up:"
echo "  node-1: http://localhost:8081/api/v1/schedules"
echo "  node-2: http://localhost:8082/api/v1/schedules"
echo "run ./scripts/smoke-test.sh in another terminal to watch them compete."
wait
