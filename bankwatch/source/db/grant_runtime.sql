-- BankWatch / PostgreSQL 16. schema.sql·seed.sql 적용 후 스키마 소유자로 실행한다.
-- bankwatch_app LOGIN 역할과 접속 비밀번호는 별도 로컬 초기화에서 준비한다.
-- 이 파일은 역할·비밀번호·운영자 계정·테이블을 생성하거나 데이터를 변경하지 않는다.
-- psql -X -v ON_ERROR_STOP=1 -d bankwatch_db -f db/grant_runtime.sql
-- 이후 초기 운영자는 소유자 권한의 별도 작업에서 로컬 비밀번호를 BCrypt로 해시한다.
-- bankwatch_app은 공통 DB 역할이다. ADMIN/ANALYST·담당자·버전 검사는 서비스 책임이다.

BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

DO $check_runtime_role$
DECLARE
    v_owner oid;
    v_app record;
BEGIN
    SELECT n.nspowner INTO v_owner
    FROM pg_catalog.pg_namespace AS n WHERE n.nspname = 'bankwatch';
    IF v_owner IS NULL THEN
        RAISE EXCEPTION 'schema.sql을 먼저 적용해야 합니다.' USING ERRCODE = '3F000';
    END IF;
    IF v_owner <> CURRENT_USER::pg_catalog.regrole::oid THEN
        RAISE EXCEPTION 'bankwatch 스키마 소유자로 실행해야 합니다.' USING ERRCODE = '42501';
    END IF;

    SELECT r.* INTO v_app FROM pg_catalog.pg_roles AS r WHERE r.rolname = 'bankwatch_app';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'bankwatch_app LOGIN 역할을 먼저 준비해야 합니다.' USING ERRCODE = '42704';
    END IF;
    IF NOT v_app.rolcanlogin OR v_app.rolsuper OR v_app.rolcreatedb OR v_app.rolcreaterole
       OR v_app.rolreplication OR v_app.rolbypassrls OR v_app.oid = v_owner
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members WHERE member = v_app.oid)
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_database
                  WHERE datname = pg_catalog.current_database() AND datdba = v_app.oid)
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_class AS c
                  JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
                  WHERE n.nspname = 'bankwatch' AND c.relowner = v_app.oid)
       OR EXISTS (SELECT 1 FROM pg_catalog.pg_proc AS p
                  JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                  WHERE n.nspname = 'bankwatch' AND p.proowner = v_app.oid) THEN
        RAISE EXCEPTION 'bankwatch_app은 관리 권한·역할 멤버십·업무 객체 소유권이 없는 전용 LOGIN 역할이어야 합니다.'
            USING ERRCODE = '55000';
    END IF;
END;
$check_runtime_role$;

-- 현재 업무 스키마의 직접 권한을 재설정한다. 다른 스키마·DB의 권한은 변경하지 않는다.
REVOKE ALL PRIVILEGES ON SCHEMA bankwatch FROM PUBLIC, bankwatch_app;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA bankwatch FROM PUBLIC, bankwatch_app;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA bankwatch FROM PUBLIC, bankwatch_app;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA bankwatch FROM PUBLIC, bankwatch_app;

-- 테이블 수준 REVOKE만으로 남을 수 있는 기존 컬럼 권한도 제거한다. 자식 파티션 포함.
DO $reset_column_grants$
DECLARE
    v_table record;
BEGIN
    FOR v_table IN
        SELECT c.relname,
               pg_catalog.string_agg(pg_catalog.format('%I', a.attname), ', ' ORDER BY a.attnum) AS columns
        FROM pg_catalog.pg_class AS c
        JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
        JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid
        WHERE n.nspname = 'bankwatch' AND c.relkind IN ('r', 'p')
          AND a.attnum > 0 AND NOT a.attisdropped
        GROUP BY c.oid, c.relname
    LOOP
        EXECUTE pg_catalog.format(
            'REVOKE ALL PRIVILEGES (%s) ON TABLE %I.%I FROM PUBLIC, bankwatch_app',
            v_table.columns, 'bankwatch', v_table.relname
        );
    END LOOP;
END;
$reset_column_grants$;

GRANT USAGE ON SCHEMA bankwatch TO bankwatch_app;
GRANT SELECT ON TABLE
    bankwatch.operator_user, bankwatch.customer_account,
    bankwatch.security_event, bankwatch.detection_rule,
    bankwatch.alert, bankwatch.alert_event,
    bankwatch.incident, bankwatch.incident_history
TO bankwatch_app;

-- 원본 event_id는 입력값이다. identity ID·서버 시각·초기 버전은 DB 생성값을 쓴다.
-- 아래에 열거한 입력 컬럼만 허용한다.
GRANT INSERT (occurred_at, event_id, account_id, device_ref, event_type, is_new_device)
    ON TABLE bankwatch.security_event TO bankwatch_app;
GRANT INSERT (rule_id, account_id, bucket_start)
    ON TABLE bankwatch.alert TO bankwatch_app;
GRANT INSERT (alert_id, event_occurred_at, event_id, is_anchor)
    ON TABLE bankwatch.alert_event TO bankwatch_app;
GRANT INSERT (alert_id, assignee_id)
    ON TABLE bankwatch.incident TO bankwatch_app;
GRANT INSERT (incident_id, actor_id, action_type, reason)
    ON TABLE bankwatch.incident_history TO bankwatch_app;

-- 한 컬럼 이상의 UPDATE 권한으로 고객·사건의 FOR UPDATE도 가능하다.
GRANT UPDATE (lock_incident_id) ON TABLE bankwatch.customer_account TO bankwatch_app;
GRANT UPDATE (closed_at, resolution, version) ON TABLE bankwatch.incident TO bankwatch_app;

GRANT USAGE ON SEQUENCE
    bankwatch.alert_alert_id_seq,
    bankwatch.incident_incident_id_seq,
    bankwatch.incident_history_history_id_seq
TO bankwatch_app;
GRANT EXECUTE ON FUNCTION bankwatch.lock_alert_for_claim(bigint) TO bankwatch_app;

-- 원본·경보·근거·이력의 UPDATE/DELETE, 모든 테이블의 DELETE/TRUNCATE는 허용하지 않는다.
-- 원본은 부모 테이블로 조회·입력한다. 자식 파티션에 직접 권한을 주지 않는다.
-- 새 객체의 자동 GRANT는 없다. DDL을 추가하면 이 파일을 함께 검토한다.
COMMIT;
