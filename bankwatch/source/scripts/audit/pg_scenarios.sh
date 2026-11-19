#!/usr/bin/env bash
# BankWatch SQL-level scenario verification on a dedicated local PG16 cluster.
# Executes the verbatim Java SQL constants (prepared by sqlprep.py) as bankwatch_app,
# in the same order and isolation level the services use. Not a substitute for -Ppostgres-it.
# Usage: PGDATABASE=bankwatch_test_x pg_scenarios.sh <project-root> <prepared.sql>
# Requires roles: schema owner (BW_OWNER_ROLE), bankwatch_app, verifier (BW_VERIFIER_ROLE, member of pg_signal_backend).
# WARNING: TRUNCATEs the bankwatch schema of that dedicated test DB only.
set -euo pipefail
ROOT=$1; PREP=$2
export PGHOST=${PGHOST:-localhost} PGPORT=${PGPORT:-5432} PGDATABASE=${PGDATABASE:?set PGDATABASE to a dedicated bankwatch_test_* DB}
[[ "$PGDATABASE" == bankwatch_test_* ]] || { echo "refuse: PGDATABASE must be bankwatch_test_*"; exit 2; }
OWNER="psql -X -q -At -U ${BW_OWNER_ROLE:-bw_schema_owner} -v ON_ERROR_STOP=1"
APP="psql -X -q -At -U bankwatch_app -v ON_ERROR_STOP=1"
pass=0; fail=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "PASS $1 => $3"; pass=$((pass+1)); else echo "FAIL $1 expected[$2] got[$3]"; fail=$((fail+1)); fi
}
reset() {
  $OWNER -c "TRUNCATE bankwatch.incident_history, bankwatch.alert_event, bankwatch.incident, bankwatch.alert,
    bankwatch.security_event, bankwatch.customer_account, bankwatch.detection_rule, bankwatch.operator_user RESTART IDENTITY"
  $OWNER -f "$ROOT/db/seed.sql" >/dev/null
  # OperatorBootstrap emulation: placeholder non-login hash only (no real credential).
  $OWNER -c "INSERT INTO bankwatch.operator_user (login_id,password_hash,display_name,role) VALUES
    ('admin','!placeholder','관제 관리자','ADMIN'),('analyst_a','!placeholder','관제 담당자 A','ANALYST'),
    ('analyst_b','!placeholder','관제 담당자 B','ANALYST')"
}
# Build an import transaction (READ COMMITTED) following EventService: rows sorted by time,id;
# per row QRY-02 then QRY-03; abort if customer missing.
import_sql() { # csv -> SQL using \gset; missing customer => explicit error that aborts tx
  python3 -I - "$1" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1], encoding="utf-8")))
rows.sort(key=lambda r: (r["occurred_at"], int(r["event_id"])))
print("BEGIN ISOLATION LEVEL READ COMMITTED;")
for r in rows:
    nd = "NULL" if r["is_new_device"] == "" else r["is_new_device"]
    # QRY-02 verbatim; no row => psql \\gset error => ON_ERROR_STOP exits => tx rolled back
    print(f"EXECUTE eventservice_customer('{r['customer_ref']}') \\gset")
    print(f"EXECUTE eventservice_insert('{r['occurred_at']}', {r['event_id']}, :account_id, '{r['device_ref']}', '{r['event_type']}', {nd});")
print("COMMIT;")
PY
}
reset
# ---- SC01 normal 7 rows -------------------------------------------------------------
{ cat "$PREP"; import_sql "$ROOT/samples/events-r02.csv"; } | $APP >/dev/null
check "SC01 import rows" 7 "$($OWNER -c 'SELECT count(*) FROM bankwatch.security_event')"
check "SC01 rows routed to 2026_11" 7 "$($OWNER -c 'SELECT count(*) FROM bankwatch.security_event_2026_11')"
CAND=$( { cat "$PREP"; echo "BEGIN ISOLATION LEVEL REPEATABLE READ; EXECUTE detectionservice_candidates('2026-11-02T10:00:00+09:00','2026-11-02T10:10:00+09:00',5); COMMIT;"; } | $APP -F' ')
echo "candidates: $CAND"
read -r C_ACC C_BUCKET C_BUCKET_T C_ANCHOR_AT C_ANCHOR_AT_T C_ANCHOR_ID C_FAIL C_COUNT <<<"$CAND"
check "SC01 candidate buckets" 1 "$(echo "$CAND" | grep -c .)"
check "SC01 candidate successes (candidate_count)" 2 "$C_COUNT"
check "SC01 anchor event_id" 6 "$C_ANCHOR_ID"
check "SC01 preceding failures" 5 "$C_FAIL"
BUCKET="$C_BUCKET $C_BUCKET_T"; ANCHOR="$C_ANCHOR_AT $C_ANCHOR_AT_T"
DET=$( { cat "$PREP"; cat <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ;
EXECUTE detectionservice_alert(1, $C_ACC, '$BUCKET');
EXECUTE detectionservice_evidence(1, '$ANCHOR', $C_ANCHOR_ID, $C_ACC, '$BUCKET');
EXECUTE detectionservice_check_evidence(1, 1, '$BUCKET', $C_ACC, '$ANCHOR', $C_ANCHOR_ID);
COMMIT;
SQL
} | $APP -F' ')
echo "detect: $DET"
check "SC01 alerts" 1 "$($OWNER -c 'SELECT count(*) FROM bankwatch.alert')"
check "SC01 evidence rows" 6 "$($OWNER -c 'SELECT count(*) FROM bankwatch.alert_event')"
check "SC01 anchors" "6" "$($OWNER -c 'SELECT event_id FROM bankwatch.alert_event WHERE is_anchor')"
check "SC01 QRY-23 total/anchors/failures" "6 1 5" "$(echo "$DET" | tail -1)"
# re-detection: same candidate, ON CONFLICT DO NOTHING returns no row
RE=$( { cat "$PREP"; echo "BEGIN ISOLATION LEVEL REPEATABLE READ; EXECUTE detectionservice_alert(1, $C_ACC, '$BUCKET'); COMMIT;"; } | $APP)
check "SC01 re-detect new alerts" "" "$RE"
check "SC01 evidence unchanged after re-detect" 6 "$($OWNER -c 'SELECT count(*) FROM bankwatch.alert_event')"
# re-import same file: all duplicates (INSERT returns 0 rows -> QRY-04 identical)
{ cat "$PREP"; import_sql "$ROOT/samples/events-r02.csv"; } | $APP >/dev/null
check "SC01 duplicate re-import keeps 7" 7 "$($OWNER -c 'SELECT count(*) FROM bankwatch.security_event')"

# ---- TEST-05 boundaries: 0 candidates -------------------------------------------------
{ cat "$PREP"; import_sql "$ROOT/samples/events-boundaries.csv"; } | $APP >/dev/null
BC=$( { cat "$PREP"; echo "BEGIN ISOLATION LEVEL REPEATABLE READ; EXECUTE detectionservice_candidates('2026-11-02T11:00:00+09:00','2026-11-02T12:30:00+09:00',5); COMMIT;"; } | $APP | grep -c . || true)
check "TEST05 boundary candidates" 0 "$BC"

# ---- SC03 missing customer after first good INSERT ------------------------------------
BEFORE=$($OWNER -c 'SELECT count(*) FROM bankwatch.security_event')
set +e
OUT=$( { cat "$PREP"; import_sql "$ROOT/samples/events-invalid-customer.csv"; } | $APP 2>&1 >/dev/null); RC=$?
set -e
check "SC03 import aborted" 1 "$([[ $RC -ne 0 ]] && echo 1 || echo 0)"
check "SC03 event 8 rolled back" 0 "$($OWNER -c 'SELECT count(*) FROM bankwatch.security_event WHERE event_id=8')"
check "SC03 total unchanged" "$BEFORE" "$($OWNER -c 'SELECT count(*) FROM bankwatch.security_event')"

# ---- SC01 response: claim -> lock -> close -> admin unlock after close ----------------
RESP=$( { cat "$PREP"; cat <<'SQL'
BEGIN; EXECUTE incidentclaimservice_operator(2); SELECT bankwatch.lock_alert_for_claim(1);
EXECUTE incidentclaimservice_current(1);
EXECUTE incidentclaimservice_insert_incident(1, 2);
EXECUTE incidentclaimservice_insert_history(1, 2, '사건 담당자로 배정'); COMMIT;
BEGIN; EXECUTE incidentactionservice_current(1); EXECUTE incidentactionservice_lock_customer(1);
EXECUTE incidentactionservice_lock_incident(1);
EXECUTE incidentactionservice_change_cause(1, 1, NULL);
EXECUTE incidentactionservice_bump_version(1, 0);
EXECUTE incidentactionservice_insert_history(1, 2, 'LOCK_ACCOUNT', '선행 실패 5건 뒤 신규 기기 성공'); COMMIT;
BEGIN; EXECUTE incidentactionservice_lock_customer(1); EXECUTE incidentactionservice_lock_incident(1);
EXECUTE incidentactionservice_close_incident('CONFIRMED', 1, 1);
EXECUTE incidentactionservice_insert_history(1, 2, 'CLOSE', '본인 확인 결과 사고 확인'); COMMIT;
SQL
} | $APP -F' ')
check "SC01 closed but still locked" "1 t" "$($OWNER -F' ' -c 'SELECT c.lock_incident_id, i.closed_at IS NOT NULL FROM bankwatch.customer_account c, bankwatch.incident i WHERE c.account_id=1')"
check "SC01 version after lock+close" 2 "$($OWNER -c 'SELECT version FROM bankwatch.incident')"
{ cat "$PREP"; echo "BEGIN; EXECUTE incidentactionservice_lock_customer(1); EXECUTE incidentactionservice_lock_incident(1);
EXECUTE incidentactionservice_change_cause(NULL, 1, 1); EXECUTE incidentactionservice_bump_version(1, 2);
EXECUTE incidentactionservice_insert_history(1, 1, 'UNLOCK_ACCOUNT', '관리자 해제'); COMMIT;"; } | $APP >/dev/null
check "SC01 admin unlock after close" "" "$($OWNER -c 'SELECT lock_incident_id FROM bankwatch.customer_account WHERE account_id=1')"
check "SC01 history actions" "CLAIM,LOCK_ACCOUNT,CLOSE,UNLOCK_ACCOUNT" "$($OWNER -c "SELECT string_agg(action_type, ',' ORDER BY history_id) FROM bankwatch.incident_history")"
check "SC01 stale version bump rejected (0 rows)" "" "$( { cat "$PREP"; echo "EXECUTE incidentactionservice_bump_version(1, 0);"; } | $APP)"

# ---- TEST-09 privileges / constraints / partition --------------------------------------
st() { $APP -c "$1" 2>&1 | grep -o 'ERROR: .*' | head -1 || true; }
code() { psql -X -q -At -U "$2" -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -c "$1" 2>&1 | grep -oE 'ERROR:  [0-9A-Z]{5}' | awk '{print $2}' | head -1 || true; }
check "TEST09 app UPDATE security_event denied" 42501 "$(code "UPDATE bankwatch.security_event SET device_ref='x'" bankwatch_app)"
check "TEST09 app DELETE alert denied" 42501 "$(code "DELETE FROM bankwatch.alert" bankwatch_app)"
check "TEST09 app UPDATE incident.assignee_id denied" 42501 "$(code "UPDATE bankwatch.incident SET assignee_id=3" bankwatch_app)"
check "TEST09 app TRUNCATE denied" 42501 "$(code "TRUNCATE bankwatch.incident_history" bankwatch_app)"
check "TEST09 app UPDATE detection_rule denied" 42501 "$(code "UPDATE bankwatch.detection_rule SET threshold_count=3" bankwatch_app)"
check "TEST09 no partition for 2027-01 (23514)" 23514 "$(code "INSERT INTO bankwatch.security_event (occurred_at,event_id,account_id,device_ref,event_type,is_new_device) VALUES ('2027-01-01T00:00:00+09:00',99,1,'d','LOGIN_FAILED',NULL)" bankwatch_app)"
check "TEST09 KST month boundary 2026-12-01 00:00+09 routes to 2026_12" "bankwatch.security_event_2026_12" "$($OWNER -c "BEGIN; INSERT INTO bankwatch.security_event (occurred_at,event_id,account_id,device_ref,event_type,is_new_device) VALUES ('2026-12-01T00:00:00+09:00',98,1,'d','LOGIN_FAILED',NULL) RETURNING tableoid::regclass; ROLLBACK;" | grep security_event)"
check "TEST09 direct FK violation (23503)" 23503 "$(code "INSERT INTO bankwatch.alert_event (alert_id,event_occurred_at,event_id,is_anchor) VALUES (1,'2026-11-02T10:00:00+09:00',424242,false)" bankwatch_app)"
check "TEST09 success needs is_new_device (23514)" 23514 "$(code "INSERT INTO bankwatch.security_event (occurred_at,event_id,account_id,device_ref,event_type,is_new_device) VALUES ('2026-11-03T00:00:00+09:00',97,1,'d','LOGIN_SUCCESS',NULL)" bankwatch_app)"
check "TEST09 second anchor rejected (23505)" 23505 "$(code "INSERT INTO bankwatch.alert_event (alert_id,event_occurred_at,event_id,is_anchor) SELECT 1, occurred_at, event_id, true FROM bankwatch.security_event WHERE event_id=7" bankwatch_app)"
check "TEST09 duplicate alert UQ (23505)" 23505 "$(code "INSERT INTO bankwatch.alert (rule_id,account_id,bucket_start) VALUES (1,1,'2026-11-02T10:00:00+09:00')" bankwatch_app)"
check "TEST09 second incident per alert UQ (23505)" 23505 "$(code "INSERT INTO bankwatch.incident (alert_id,assignee_id) VALUES (1,3)" bankwatch_app)"
check "TEST09 blank reason CHECK (23514)" 23514 "$(code "INSERT INTO bankwatch.incident_history (incident_id,actor_id,action_type,reason) VALUES (1,2,'CLOSE',E' \\t')" bankwatch_app)"
check "TEST09 app lacks pg_signal_backend" f "$($APP -c "SELECT pg_has_role('bankwatch_app','pg_signal_backend','USAGE')")"

# ---- QRY-05 partition pruning on composed query text ------------------------------------
PLAN=$($APP -c "SET search_path=bankwatch; EXPLAIN (COSTS OFF) SELECT e.occurred_at FROM bankwatch.security_event e JOIN bankwatch.customer_account c ON c.account_id = e.account_id WHERE true AND c.customer_ref = 'C1001' AND e.occurred_at >= '2026-11-02T00:00:00+09:00' AND e.occurred_at < '2026-11-03T00:00:00+09:00' ORDER BY e.occurred_at DESC, e.event_id DESC LIMIT 21")
check "QRY05 pruning scans only 2026_11" "1 0 0" "$(grep -c security_event_2026_11 <<<"$PLAN") $(grep -c security_event_2026_10 <<<"$PLAN") $(grep -c security_event_2026_12 <<<"$PLAN")"
echo "TOTAL pass=$pass fail=$fail"
[[ $fail -eq 0 ]]
