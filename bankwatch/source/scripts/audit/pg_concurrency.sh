#!/usr/bin/env bash
# SC02/SC04 with genuinely concurrent PG sessions using verbatim Java SQL (prepared.sql).
# Usage: PGDATABASE=bankwatch_test_x pg_concurrency.sh <project-root> <prepared.sql> <workdir>
# WARNING: TRUNCATEs the bankwatch schema of that dedicated test DB only.
set -uo pipefail
ROOT=$1; PREP=$2; W=$3; mkdir -p "$W"
export PGHOST=${PGHOST:-localhost} PGPORT=${PGPORT:-5432} PGDATABASE=${PGDATABASE:?set PGDATABASE to a dedicated bankwatch_test_* DB}
[[ "$PGDATABASE" == bankwatch_test_* ]] || { echo "refuse: PGDATABASE must be bankwatch_test_*"; exit 2; }
OWNER="psql -X -q -At -U ${BW_OWNER_ROLE:-bw_schema_owner} -v ON_ERROR_STOP=1"
pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "PASS $1 => $3"; pass=$((pass+1)); else echo "FAIL $1 expected[$2] got[$3]"; fail=$((fail+1)); fi; }
app() { # name, sql-file ; runs as bankwatch_app with application_name
  PGAPPNAME="$1" psql -X -q -At -F' ' -U bankwatch_app -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -f "$2" >"$W/$1.out" 2>"$W/$1.err"; echo $? >"$W/$1.rc"
}
setup() { # reset, import normal 7, create alert 1 with 6 evidence rows (verbatim detection SQL)
  $OWNER -c "TRUNCATE bankwatch.incident_history, bankwatch.alert_event, bankwatch.incident, bankwatch.alert,
    bankwatch.security_event, bankwatch.customer_account, bankwatch.detection_rule, bankwatch.operator_user RESTART IDENTITY"
  $OWNER -f "$ROOT/db/seed.sql" >/dev/null
  $OWNER -c "INSERT INTO bankwatch.operator_user (login_id,password_hash,display_name,role) VALUES
    ('admin','!placeholder','관제 관리자','ADMIN'),('analyst_a','!placeholder','관제 담당자 A','ANALYST'),
    ('analyst_b','!placeholder','관제 담당자 B','ANALYST')"
  $OWNER -c "INSERT INTO bankwatch.security_event (occurred_at,event_id,account_id,device_ref,event_type,is_new_device)
    SELECT v.t::timestamptz, v.id, 1, v.d, v.ty, v.nd FROM (VALUES
     ('2026-11-02T10:00:30+09:00',1,'known-device','LOGIN_FAILED',NULL::boolean),('2026-11-02T10:01:00+09:00',2,'known-device','LOGIN_FAILED',NULL),
     ('2026-11-02T10:02:00+09:00',3,'known-device','LOGIN_FAILED',NULL),('2026-11-02T10:03:00+09:00',4,'known-device','LOGIN_FAILED',NULL),
     ('2026-11-02T10:04:00+09:00',5,'known-device','LOGIN_FAILED',NULL),('2026-11-02T10:05:00+09:00',6,'new-device-a','LOGIN_SUCCESS',true),
     ('2026-11-02T10:07:00+09:00',7,'new-device-b','LOGIN_SUCCESS',true)) v(t,id,d,ty,nd)"
  { cat "$PREP"; echo "BEGIN ISOLATION LEVEL REPEATABLE READ;
    EXECUTE detectionservice_alert(1, 1, '2026-11-02T10:00:00+09:00');
    EXECUTE detectionservice_evidence(1, '2026-11-02T10:05:00+09:00', 6, 1, '2026-11-02T10:00:00+09:00'); COMMIT;"; } \
    | psql -X -q -At -U bankwatch_app -v ON_ERROR_STOP=1 >/dev/null
}
claim_file() { # actor sleep
  { cat "$PREP"; cat <<SQL
BEGIN ISOLATION LEVEL READ COMMITTED;
EXECUTE incidentclaimservice_operator($1);
SELECT 'LOCK', bankwatch.lock_alert_for_claim(1);
EXECUTE incidentclaimservice_operator($1);
SELECT 'CURRENT', coalesce(i.incident_id::text,'none'), coalesce(i.assignee_id::text,'none')
  FROM bankwatch.alert a LEFT JOIN bankwatch.incident i ON i.alert_id=a.alert_id WHERE a.alert_id=1;
\\if $3
EXECUTE incidentclaimservice_insert_incident(1, $1);
EXECUTE incidentclaimservice_insert_history(currval('bankwatch.incident_incident_id_seq'), $1, '사건 담당자로 배정');
SELECT pg_sleep($2);
COMMIT;
\\else
ROLLBACK;
\\endif
SQL
  }
}

# ---------------- SC02-a: different analysts A/B claim the same alert --------------------
setup
claim_file 2 2 true  >"$W/claimA.sql"
claim_file 3 0 false >"$W/claimB.sql"   # B: after lock, sees winner => service returns 409 and rolls back
app claimA "$W/claimA.sql" & PA=$!
sleep 0.7
T0=$(date +%s.%N); app claimB "$W/claimB.sql"; T1=$(date +%s.%N); wait $PA
check "SC02 A commit rc" 0 "$(cat "$W/claimA.rc")"
check "SC02 B waited on alert row lock (>=1s)" 1 "$(python3 -c "print(int($T1-$T0>=1.0))")"
check "SC02 B sees A's committed incident after lock" "CURRENT 1 2" "$(grep ^CURRENT "$W/claimB.out")"
check "SC02 incidents=1 history=1 assignee=A" "1 1 2" "$($OWNER -F' ' -c 'SELECT (SELECT count(*) FROM bankwatch.incident),(SELECT count(*) FROM bankwatch.incident_history),(SELECT assignee_id FROM bankwatch.incident)')"

# ---------------- SC02-b: winner A in two independent sessions, same version 0 ------------
lock_file() { # sleep-before-commit
  { cat "$PREP"; cat <<SQL
BEGIN ISOLATION LEVEL READ COMMITTED;
EXECUTE incidentactionservice_current(1);
EXECUTE incidentactionservice_lock_customer(1);
EXECUTE incidentactionservice_lock_incident(1);
SELECT 'RECHECK', i.version, coalesce(c.lock_incident_id::text,'null') FROM bankwatch.incident i
  JOIN bankwatch.alert a ON a.alert_id=i.alert_id JOIN bankwatch.customer_account c ON c.account_id=a.account_id WHERE i.incident_id=1;
\\if $2
EXECUTE incidentactionservice_change_cause(1, 1, NULL);
EXECUTE incidentactionservice_bump_version(1, 0);
EXECUTE incidentactionservice_insert_history(1, 2, 'LOCK_ACCOUNT', '보호 잠금 세션1');
SELECT pg_sleep($1);
COMMIT;
\\else
SELECT 'STALE_BUMP_ROWS', count(*) FROM (SELECT 1 FROM bankwatch.incident WHERE incident_id=1 AND version=0) s;
ROLLBACK;
\\endif
SQL
  }
}
lock_file 2 true  >"$W/s1.sql"
lock_file 0 false >"$W/s2.sql"   # session 2: recheck shows version 1 => validateCurrent 409, rollback
app s1 "$W/s1.sql" & P1=$!
sleep 0.7
T0=$(date +%s.%N); app s2 "$W/s2.sql"; T1=$(date +%s.%N); wait $P1
check "SC02 session1 commit rc" 0 "$(cat "$W/s1.rc")"
check "SC02 session2 blocked on customer lock (>=1s)" 1 "$(python3 -c "print(int($T1-$T0>=1.0))")"
check "SC02 session2 recheck sees version1+cause1" "RECHECK 1 1" "$(grep ^RECHECK "$W/s2.out")"
check "SC02 stale version0 matches no row" "STALE_BUMP_ROWS 0" "$(grep ^STALE "$W/s2.out")"
check "SC02 final version=1 lock=1 LOCK history=1" "1 1 1" "$($OWNER -F' ' -c "SELECT (SELECT version FROM bankwatch.incident),(SELECT lock_incident_id FROM bankwatch.customer_account WHERE account_id=1),(SELECT count(*) FROM bankwatch.incident_history WHERE action_type='LOCK_ACCOUNT')")"

# ---------------- SC04: verifier terminates app backend after cause/version UPDATE ----------
setup
{ cat "$PREP"; echo "BEGIN; SELECT bankwatch.lock_alert_for_claim(1); EXECUTE incidentclaimservice_insert_incident(1, 2);
  EXECUTE incidentclaimservice_insert_history(1, 2, '사건 담당자로 배정'); COMMIT;"; } | psql -X -q -At -U bankwatch_app -v ON_ERROR_STOP=1 >/dev/null
{ cat "$PREP"; cat <<'SQL'
BEGIN ISOLATION LEVEL READ COMMITTED;
EXECUTE incidentactionservice_lock_customer(1);
EXECUTE incidentactionservice_lock_incident(1);
EXECUTE incidentactionservice_change_cause(1, 1, NULL);
EXECUTE incidentactionservice_bump_version(1, 0);
SELECT 'UPDATED_IN_TX', (SELECT lock_incident_id FROM bankwatch.customer_account WHERE account_id=1), (SELECT version FROM bankwatch.incident WHERE incident_id=1);
SELECT pg_sleep(20);
EXECUTE incidentactionservice_insert_history(1, 2, 'LOCK_ACCOUNT', '도달하면 안 됨');
COMMIT;
SQL
} >"$W/sc04.sql"
app bw_sc04 "$W/sc04.sql" & P4=$!
for _ in $(seq 50); do
  [[ "$($OWNER -c "SELECT count(*) FROM pg_stat_activity WHERE application_name='bw_sc04' AND query LIKE '%pg_sleep(20)%' AND state='active'")" == 1 ]] && break; sleep 0.1
done
# app role lacks pg_signal_backend: it cannot signal another role's backend (verifier session).
PGAPPNAME=bw_vsleep psql -X -q -At -U ${BW_VERIFIER_ROLE:-bw_verifier} -c "SELECT pg_sleep(3)" >/dev/null 2>&1 & PV=$!
sleep 0.5
DENY=$(psql -X -q -At -U bankwatch_app -v VERBOSITY=verbose -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='bw_vsleep'" 2>&1 | grep -oE 'ERROR:  [0-9A-Z]{5}' | awk '{print $2}' | head -1)
check "SC04 app role cannot terminate verifier backend (42501)" 42501 "$DENY"
wait $PV
# Observation (PostgreSQL rule, not a grant): a role may always signal backends of its own role.
check "SC04 verifier terminates app backend" t "$(psql -X -q -At -U ${BW_VERIFIER_ROLE:-bw_verifier} -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='bw_sc04' AND usename='bankwatch_app'")"
wait $P4
check "SC04 in-tx update was applied before kill" "UPDATED_IN_TX 1 1" "$(grep ^UPDATED "$W/bw_sc04.out")"
check "SC04 client got 57P01" 1 "$(grep -c '57P01' "$W/bw_sc04.err")"
check "SC04 restored lock NULL version 0 history CLAIM only" "null 0 1" "$($OWNER -F' ' -c "SELECT coalesce((SELECT lock_incident_id::text FROM bankwatch.customer_account WHERE account_id=1),'null'),(SELECT version FROM bankwatch.incident),(SELECT count(*) FROM bankwatch.incident_history)")"
# explicit new request (no automatic retry) succeeds
{ cat "$PREP"; echo "BEGIN; EXECUTE incidentactionservice_lock_customer(1); EXECUTE incidentactionservice_lock_incident(1);
  EXECUTE incidentactionservice_change_cause(1, 1, NULL); EXECUTE incidentactionservice_bump_version(1, 0);
  EXECUTE incidentactionservice_insert_history(1, 2, 'LOCK_ACCOUNT', '명시적 새 요청'); COMMIT;"; } | psql -X -q -At -U bankwatch_app -v ON_ERROR_STOP=1 >/dev/null
check "SC04 explicit new request succeeds" "1 1 2" "$($OWNER -F' ' -c "SELECT (SELECT lock_incident_id FROM bankwatch.customer_account WHERE account_id=1),(SELECT version FROM bankwatch.incident),(SELECT count(*) FROM bankwatch.incident_history)")"
echo "TOTAL pass=$pass fail=$fail"
[[ $fail -eq 0 ]]
