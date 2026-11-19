-- BankWatch: PostgreSQL 16 / 01·02 데이터 계약 기반 최초 생성 DDL.
-- 스키마 소유자로 실행한다. 신규 또는 비어 있는 bankwatch 스키마만 허용한다.
-- 검증은 기존 사용자 DB가 아닌 별도 검증 DB에서 수행한다.
-- psql 실행 예: psql -X -v ON_ERROR_STOP=1 -d bankwatch_schema_test -f db/schema.sql
-- 이 파일은 데이터·DB·스키마를 삭제하거나 기존 객체를 교체하지 않는다.

BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

CREATE SCHEMA IF NOT EXISTS bankwatch;

DO $check_empty_schema$
DECLARE
    v_namespace oid := 'bankwatch'::pg_catalog.regnamespace;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace AS n
        WHERE n.oid = v_namespace
          AND pg_catalog.pg_get_userbyid(n.nspowner) = CURRENT_USER
    ) THEN
        RAISE EXCEPTION '스키마 소유자로 실행해야 합니다.'
            USING ERRCODE = '42501';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_depend AS d
        WHERE d.refclassid = 'pg_catalog.pg_namespace'::pg_catalog.regclass
          AND d.refobjid = v_namespace
    ) THEN
        RAISE EXCEPTION 'bankwatch 스키마가 비어 있지 않습니다.'
            USING ERRCODE = '55000';
    END IF;
END;
$check_empty_schema$;

REVOKE ALL ON SCHEMA bankwatch FROM PUBLIC;

CREATE TABLE bankwatch.operator_user (
    operator_id bigint GENERATED ALWAYS AS IDENTITY,
    login_id varchar(50) NOT NULL,
    password_hash text NOT NULL,
    display_name varchar(80) NOT NULL,
    role text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    CONSTRAINT pk_operator_user PRIMARY KEY (operator_id),
    CONSTRAINT uq_operator_user_login_id UNIQUE (login_id),
    CONSTRAINT ck_operator_user_role CHECK (role IN ('ADMIN', 'ANALYST'))
);

CREATE TABLE bankwatch.customer_account (
    account_id bigint GENERATED ALWAYS AS IDENTITY,
    customer_ref varchar(40) NOT NULL,
    display_name varchar(80) NOT NULL,
    lock_incident_id bigint,
    CONSTRAINT pk_customer_account PRIMARY KEY (account_id),
    CONSTRAINT uq_customer_account_customer_ref UNIQUE (customer_ref),
    CONSTRAINT uq_customer_account_lock_incident UNIQUE (lock_incident_id)
    -- 원인 사건 FK는 incident 생성 후 추가한다. 여러 NULL은 허용한다.
);

CREATE TABLE bankwatch.security_event (
    occurred_at timestamptz NOT NULL,
    event_id bigint NOT NULL,
    account_id bigint NOT NULL,
    device_ref varchar(100) NOT NULL,
    event_type text NOT NULL,
    is_new_device boolean,
    ingested_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT pk_security_event PRIMARY KEY (occurred_at, event_id),
    CONSTRAINT fk_security_event_account FOREIGN KEY (account_id)
        REFERENCES bankwatch.customer_account (account_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT ck_security_event_positive_id CHECK (event_id > 0),
    CONSTRAINT ck_security_event_type
        CHECK (event_type IN ('LOGIN_FAILED', 'LOGIN_SUCCESS')),
    CONSTRAINT ck_security_event_new_device CHECK (
        (event_type = 'LOGIN_SUCCESS' AND is_new_device IS NOT NULL)
        OR (event_type = 'LOGIN_FAILED' AND is_new_device IS NULL)
    )
) PARTITION BY RANGE (occurred_at);

-- 물리 저장 범위: KST 월 경계 [하한, 상한). R02의 10분 논리 구간과 별개다.
CREATE TABLE bankwatch.security_event_2026_10
    PARTITION OF bankwatch.security_event
    FOR VALUES FROM ('2026-10-01 00:00:00+09:00')
               TO ('2026-11-01 00:00:00+09:00');

CREATE TABLE bankwatch.security_event_2026_11
    PARTITION OF bankwatch.security_event
    FOR VALUES FROM ('2026-11-01 00:00:00+09:00')
               TO ('2026-12-01 00:00:00+09:00');

CREATE TABLE bankwatch.security_event_2026_12
    PARTITION OF bankwatch.security_event
    FOR VALUES FROM ('2026-12-01 00:00:00+09:00')
               TO ('2027-01-01 00:00:00+09:00');

CREATE TABLE bankwatch.detection_rule (
    rule_id bigint GENERATED ALWAYS AS IDENTITY,
    rule_code text NOT NULL,
    rule_name text NOT NULL,
    window_seconds smallint NOT NULL,
    threshold_count smallint NOT NULL,
    CONSTRAINT pk_detection_rule PRIMARY KEY (rule_id),
    CONSTRAINT uq_detection_rule_code UNIQUE (rule_code)
);
-- R02 / 600초 / 5건은 seed.sql의 고정 정책이다. 컬럼 DEFAULT가 아니다.
-- grant_runtime.sql에서 앱에는 이 테이블 SELECT만 부여한다.

CREATE TABLE bankwatch.alert (
    alert_id bigint GENERATED ALWAYS AS IDENTITY,
    rule_id bigint NOT NULL,
    account_id bigint NOT NULL,
    bucket_start timestamptz NOT NULL,
    detected_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT pk_alert PRIMARY KEY (alert_id),
    CONSTRAINT fk_alert_rule FOREIGN KEY (rule_id)
        REFERENCES bankwatch.detection_rule (rule_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_alert_account FOREIGN KEY (account_id)
        REFERENCES bankwatch.customer_account (account_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT uq_alert_rule_account_bucket
        UNIQUE (rule_id, account_id, bucket_start)
);

CREATE TABLE bankwatch.alert_event (
    alert_id bigint NOT NULL,
    event_occurred_at timestamptz NOT NULL,
    event_id bigint NOT NULL,
    is_anchor boolean NOT NULL,
    CONSTRAINT pk_alert_event
        PRIMARY KEY (alert_id, event_occurred_at, event_id),
    CONSTRAINT fk_alert_event_alert FOREIGN KEY (alert_id)
        REFERENCES bankwatch.alert (alert_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_alert_event_source
        FOREIGN KEY (event_occurred_at, event_id)
        REFERENCES bankwatch.security_event (occurred_at, event_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE UNIQUE INDEX uq_alert_event_anchor
    ON bankwatch.alert_event (alert_id)
    WHERE is_anchor;

CREATE TABLE bankwatch.incident (
    incident_id bigint GENERATED ALWAYS AS IDENTITY,
    alert_id bigint NOT NULL,
    assignee_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    closed_at timestamptz,
    resolution text,
    version integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_incident PRIMARY KEY (incident_id),
    CONSTRAINT uq_incident_alert UNIQUE (alert_id),
    CONSTRAINT fk_incident_alert FOREIGN KEY (alert_id)
        REFERENCES bankwatch.alert (alert_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_incident_assignee FOREIGN KEY (assignee_id)
        REFERENCES bankwatch.operator_user (operator_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT ck_incident_version CHECK (version >= 0),
    CONSTRAINT ck_incident_closure_pair
        CHECK ((closed_at IS NULL) = (resolution IS NULL)),
    CONSTRAINT ck_incident_closed_time
        CHECK (closed_at IS NULL OR closed_at >= created_at),
    CONSTRAINT ck_incident_resolution CHECK (
        resolution IS NULL
        OR resolution IN ('CONFIRMED', 'FALSE_POSITIVE', 'INCONCLUSIVE')
    )
);

-- customer_account → incident → alert → customer_account 순환 참조를 완성한다.
ALTER TABLE bankwatch.customer_account
    ADD CONSTRAINT fk_customer_account_lock_incident
    FOREIGN KEY (lock_incident_id)
    REFERENCES bankwatch.incident (incident_id)
    ON UPDATE NO ACTION ON DELETE NO ACTION;

CREATE TABLE bankwatch.incident_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY,
    incident_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    action_type text NOT NULL,
    reason varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
    CONSTRAINT pk_incident_history PRIMARY KEY (history_id),
    CONSTRAINT fk_incident_history_incident FOREIGN KEY (incident_id)
        REFERENCES bankwatch.incident (incident_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT fk_incident_history_actor FOREIGN KEY (actor_id)
        REFERENCES bankwatch.operator_user (operator_id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT ck_incident_history_action CHECK (
        action_type IN ('CLAIM', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'CLOSE')
    ),
    CONSTRAINT ck_incident_history_reason
        CHECK (reason ~ '[^[:space:]]')
);
-- 사유는 공백·탭·개행만으로 구성할 수 없다. 길이 상한은 varchar(2000)이다.

-- 상태는 조회 시 계산한다. 별도 상태 문자열이나 상태 동기화 트리거는 없다.
-- 사건 없음: 미배정 / incident.closed_at NULL: 조사 중 / 값 존재: 종료.
-- customer_account.lock_incident_id NULL: 접근 가능 / 값 존재: 보호 잠금.
-- 사건 종료는 계정 보호를 자동 해제하지 않는다.

-- 서비스 트랜잭션 경계(일반 CHECK로 보장하지 않는 교차 행 조건):
-- 1. REPEATABLE READ 탐지에서 같은 고객·고정 10분 구간·신규 성공·엄격한
--    선행 실패를 확인한다. 같은 시각 실패는 제외하고 시각·ID 순 첫 후보를 택한다.
-- 2. 신규 경보와 대표 정확히 1개·실패 5개 이상 근거를 저장·검사한 뒤 함께 커밋한다.
--    UNIQUE로 제외된 기존 경보의 최초 근거는 재탐지·동시 탐지 때 갱신하지 않는다.
-- 3. ANALYST 선점은 아래 경보 잠금 뒤 같은 연결의 별도 SELECT로 사건을 확인하고
--    incident와 CLAIM 이력을 함께 저장한다. 이 함수는 인증·사건 생성을 하지 않는다.
-- 4. 조치는 고객 → 사건 순서로 잠근 뒤 역할·담당자·상태·기대 버전·고객과
--    잠금 원인 사건의 일치를 재검사하고 원인·버전·이력을 함께 저장한다.
--    종료한 원인 사건에서도 ADMIN 해제는 허용한다. actor_id는 세션에서 얻는다.
-- 5. CSV의 형식·UTF-8·크기·중복 내용 비교와 파일 전체 롤백은 서비스 책임이다.

CREATE FUNCTION bankwatch.lock_alert_for_claim(p_alert_id bigint)
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $lock_alert$
BEGIN
    PERFORM 1
    FROM bankwatch.alert AS a
    WHERE a.alert_id = p_alert_id
    FOR UPDATE;
    RETURN FOUND;
END;
$lock_alert$;

REVOKE EXECUTE ON FUNCTION bankwatch.lock_alert_for_claim(bigint) FROM PUBLIC;

COMMENT ON FUNCTION bankwatch.lock_alert_for_claim(bigint) IS
    '같은 트랜잭션에서 경보 한 행만 잠금. 존재하면 true, 없거나 인자가 NULL이면 false. 권한 검사는 서비스에서 먼저 수행.';

-- grant_runtime.sql의 접점(이 파일에서는 앱 역할 생성·GRANT를 실행하지 않음):
-- GRANT USAGE ON SCHEMA bankwatch TO bankwatch_app;
-- GRANT EXECUTE ON FUNCTION bankwatch.lock_alert_for_claim(bigint) TO bankwatch_app;
-- 경보에는 SELECT·INSERT만 주고 행 잠금을 위해 UPDATE를 추가하지 않는다.
-- 원본·경보·근거·이력 UPDATE/DELETE는 금지하고 고객·사건 UPDATE는 컬럼별로 제한한다.
-- identity INSERT에 필요한 시퀀스 권한도 해당 파일에서 최소 범위로 부여한다.

-- 성능 기준: PK·일반 UQ·대표 부분 UQ만 이 파일에서 생성한다.
-- QRY-05 비교 후보 security_event(account_id,occurred_at,event_id)는 설치하지 않는다.
-- 10~12월 합성 30,000행과 같은 고객·11월 기간·바인딩·질의를 고정해 비교한다.
-- 먼저 같은 인덱스에서 enable_partition_pruning on/off를 비교한다.
-- 다음으로 pruning on을 유지한 채 별도 검증 DB에서 후보 인덱스만 추가 전/후 측정한다.
-- 후보 추가문(비실행):
-- CREATE INDEX idx_security_event_account_time
--     ON bankwatch.security_event (account_id, occurred_at, event_id);
-- EXPLAIN (ANALYZE, BUFFERS)의 원문·행 수·시간·버퍼·환경을 기록한다.
-- 경보 정렬·이력 조회의 추가 성능 인덱스도 실제 질의 검토 전에는 설치하지 않는다.

COMMIT;
