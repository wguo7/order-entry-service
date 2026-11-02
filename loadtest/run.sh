#!/usr/bin/env bash
# Starts the service against an emptied database, warms it up, then runs the load protocol.
# Every row lands in results/load.csv; the Tomcat access log (server-side time) in results/raw/.
# Usage: loadtest/run.sh   (needs Postgres on localhost:5432 with the orders database, and a built jar)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p results/raw
OUT=results/load.csv
rm -f "$OUT" results/raw/access.log
psql "postgresql://orders:orders@localhost:5432/orders" -qc \
  "DO \$\$ BEGIN IF to_regclass('orders') IS NOT NULL THEN TRUNCATE idempotency_keys, order_events, orders; END IF; END \$\$"

java -jar target/order-entry-service-0.1.0.jar \
  --server.tomcat.accesslog.enabled=true --server.tomcat.accesslog.directory="$PWD/results/raw" \
  --server.tomcat.accesslog.prefix=access --server.tomcat.accesslog.suffix=.log \
  --server.tomcat.accesslog.rotate=false --server.tomcat.accesslog.buffered=true \
  '--server.tomcat.accesslog.pattern=%{begin:msec}t %D %s %m %U' > results/raw/server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER' EXIT
for _ in $(seq 1 60); do curl -sf localhost:8080/actuator/health >/dev/null && break; sleep 1; done

load() { java loadtest/LoadTest.java "$@" http://localhost:8080 "$OUT" | tail -1; }
load create 500 30 0 > /dev/null            # server warm-up, not used
for _ in 1 2 3 4 5; do load create 500 60 10; done
for rate in 1000 2000 4000 8000; do load create "$rate" 30 10; done
load get 500 60 10
