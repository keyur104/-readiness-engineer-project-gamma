#!/bin/bash
# Phase 2: filing rounds -> invariants -> audit -> amendment check -> bench -> summary.
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
J="${JAVA_BIN:-java}"
PSQL="${PSQL_BIN:-psql}"
JAR="target/readiness-1.0.0.jar"
R="docs/acceptance/results.txt"

check() {
  if [ "$2" = "0" ]; then echo "[PASS] $1 -- $3" >> "$R"; else echo "[FAIL] $1 -- $3" >> "$R"; fi
}

echo "########## 6. FILING ROUNDS"
for round in 1 2 3 4; do
  for f in northstar harborline; do
    OUT=$("$J" -jar "$JAR" file --firm=$f --max-calls=20 2>&1)
    echo "$OUT" | grep -E "phase=PLAN_FILINGS|transmit |invariants  "
    echo "$OUT" | grep -q "invariants  ALL HOLD"
    check "invariants round $round $f" $? "ALL HOLD"
  done
  [ $round -lt 4 ] && sleep 40
done

echo "########## 7. VERIFY-INVARIANTS"
"$J" -jar "$JAR" verify-invariants --all-firms > /tmp/inv.txt 2>&1; EC=$?
grep -E "firm |all invariants|FAIL" /tmp/inv.txt
NP=$(grep -c '\[PASS\]' /tmp/inv.txt); NF=$(grep -c '\[FAIL\]' /tmp/inv.txt)
[ "$EC" = "0" ] && [ "$NF" = "0" ]
check "verify-invariants --all-firms" $? "exit=$EC, $NP passing, $NF failing"

echo "########## 8. VERIFY-AUDIT"
"$J" -jar "$JAR" verify-audit --all-firms > /tmp/aud.txt 2>&1; EC=$?
grep -E "events|chain|verifies" /tmp/aud.txt
[ "$EC" = "0" ]; check "verify-audit --all-firms" $? "exit=$EC"

echo "########## 9. NO FALSE AMENDMENT ITEMS"
for fid in 1 2; do
  A=$(PGPASSWORD=readiness_app_dev "$PSQL" -h localhost -p 5432 -U readiness_app -d readiness -tAq \
    -c "begin; select set_config('app.current_firm_id','$fid',true); select count(*) from app.attention_item where type='AMENDED_DATA_FOR_INFLIGHT_FILING' and resolved_at is null; commit;" | tail -1)
  [ "$A" = "0" ]; check "no false amendments firm $fid" $? "expected 0, got $A"
done

echo "########## 9b. CLIENT STATUS SPREAD (the page must triage, not flag everything)"
PGPASSWORD=readiness_app_dev "$PSQL" -h localhost -p 5432 -U readiness_app -d readiness -q \
  -c "begin; select set_config('app.current_firm_id','1',true); select status, count(*) from app.v_client_status where tax_year=2025 group by 1 order by 2 desc; commit;" 2>&1 | grep -vE "^BEGIN|set_config|^-|^ 1$|row|^$|COMMIT"
NA=$(PGPASSWORD=readiness_app_dev "$PSQL" -h localhost -p 5432 -U readiness_app -d readiness -tAq \
  -c "begin; select set_config('app.current_firm_id','1',true); select count(*) from app.v_client_status where tax_year=2025 and status='NEEDS_ATTENTION'; commit;" | tail -1)
[ "$NA" -lt 250 ]; check "clients are triaged, not all flagged" $? "$NA of 250 need attention (was 250 before the fix)"

echo "########## 10. BENCH"
"$J" -jar "$JAR" bench --firm=harborline --dir=data-final/firm-harborline > /tmp/bench.txt 2>&1; EC=$?
grep -vE "INFO |^$" /tmp/bench.txt | tail -16
[ "$EC" = "0" ]; check "bench exit code" $? "exit=$EC"

echo ""
echo "##################### ACCEPTANCE SUMMARY #####################"
cat "$R"
echo "-------------------------------------------------------------"
F=$(grep -c '^\[FAIL\]' "$R"); P=$(grep -c '^\[PASS\]' "$R")
if [ "$F" = "0" ]; then echo "ALL $P CHECKS PASSED"; else echo "$F of $((P+F)) CHECKS FAILED"; fi
