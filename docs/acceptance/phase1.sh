#!/bin/bash
# Phase 1: reset -> seed -> import -> idempotency -> determine -> revision -> incremental.
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
J="${JAVA_BIN:-java}"
PSQL="${PSQL_BIN:-psql}"
JAR="target/readiness-1.0.0.jar"
R="docs/acceptance/results.txt"
: > "$R"

check() {
  if [ "$2" = "0" ]; then echo "[PASS] $1 -- $3" >> "$R"; else echo "[FAIL] $1 -- $3" >> "$R"; fi
}

echo "########## RESET"
PGPASSWORD=0000 "$PSQL" -h localhost -p 5432 -U postgres -d readiness -q \
  -c "drop schema if exists app cascade; drop schema if exists irs_stub cascade; drop schema if exists stg cascade;" >/dev/null 2>&1
PGPASSWORD=0000 "$PSQL" -h localhost -p 5432 -U postgres -d postgres -q -f db/setup.sql >/dev/null 2>&1
echo "reset done"

echo "########## 1. SEED"
rm -rf data-final data-final-rev1
OUT=$("$J" -jar "$JAR" seed --out data-final 2>&1 | grep "phase=SEED"); echo "$OUT"
ROWS=$(echo "$OUT" | grep -o "rows=[0-9]*" | cut -d= -f2)
[ "$ROWS" = "999757" ]; check "seed row count" $? "expected 999757, got $ROWS"
"$J" -jar "$JAR" seed --out data-final-rev1 --revision 1 2>&1 | grep "phase=SEED"

echo "########## 2. IMPORT (SLA 120s)"
for f in northstar harborline; do
  OUT=$("$J" -jar "$JAR" import --firm=$f --dir=data-final/firm-$f 2>&1 | grep "phase=IMPORT"); echo "$OUT"
  MS=$(echo "$OUT" | grep -o " ms=[0-9]*" | cut -d= -f2)
  [ "$MS" -le 120000 ]; check "import SLA $f" $? "${MS}ms <= 120000ms"
  echo "$OUT" | grep -q "SLA_MISSED"; [ $? -ne 0 ]; check "import no SLA_MISSED $f" $? "flag absent"
done

echo "########## 3. VERIFY-IMPORT (idempotency)"
for f in northstar harborline; do
  "$J" -jar "$JAR" verify-import --firm=$f --dir=data-final/firm-$f > /tmp/vi_$f.txt 2>&1; EC=$?
  P=$(grep -c "\[PASS\]" /tmp/vi_$f.txt); Q=$(grep -c "\[FAIL\]" /tmp/vi_$f.txt)
  grep "IDEMPOTENT" /tmp/vi_$f.txt
  [ "$EC" = "0" ] && [ "$P" = "7" ] && [ "$Q" = "0" ]
  check "idempotency $f" $? "exit=$EC pass=$P fail=$Q (want 0/7/0)"
done

echo "########## 4. DETERMINE FULL (SLA 60s)"
for f in northstar harborline; do
  OUT=$("$J" -jar "$JAR" determine --firm=$f --full 2>&1 | grep "phase=DETERMINE"); echo "$OUT"
  MS=$(echo "$OUT" | grep -o " ms=[0-9]*" | cut -d= -f2)
  [ "$MS" -le 60000 ]; check "determination SLA $f" $? "${MS}ms <= 60000ms"
done

echo "########## 5. REVISION (exact deltas)"
for f in northstar harborline; do
  E=$(python -c "import json;d=json.load(open('data-final-rev1/revision-manifest.json'))['byFirm']['$f'];print(d['expectedInserted'],d['expectedUpdated'],d['expectedTombstoned'])")
  set -- $E; EI=$1; EU=$2; ET=$3
  OUT=$("$J" -jar "$JAR" import --firm=$f --dir=data-final-rev1/firm-$f 2>&1 | grep "phase=IMPORT"); echo "$OUT"
  GI=$(echo "$OUT" | grep -o "inserted=[0-9]*" | cut -d= -f2)
  GU=$(echo "$OUT" | grep -o "updated=[0-9]*" | cut -d= -f2)
  GT=$(echo "$OUT" | grep -o "tombstoned=[0-9]*" | cut -d= -f2)
  [ "$GI" = "$EI" ] && [ "$GU" = "$EU" ] && [ "$GT" = "$ET" ]
  check "revision deltas $f" $? "expected $EI/$EU/$ET, got $GI/$GU/$GT"
  "$J" -jar "$JAR" determine --firm=$f 2>&1 | grep "phase=DETERMINE"
done

echo "########## PHASE 1 RESULTS"
cat "$R"
echo "PHASE1 DONE"
