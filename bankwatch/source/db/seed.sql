-- BankWatch: PostgreSQL 16 / schema.sql 적용 후 스키마 소유자로 실행한다.
-- 가상 고객은 v2 값을 재사용하고 규칙은 고정 R02 하나만 준비한다.
-- psql -X -v ON_ERROR_STOP=1 -d bankwatch_db -f db/seed.sql
-- 후속 초기화 순서: 앱 DB 역할·grant_runtime.sql → 초기 운영자 생성.
-- admin·analyst_a·analyst_b의 초기 비밀번호는 별도 로컬 초기화에서 받아
-- BCrypt로 해시한다. 이 파일에는 운영자 계정·비밀번호·고정 해시를 넣지 않는다.
-- 원본 로그는 samples의 CSV 입력 단계에서 적재한다.

BEGIN;
SET LOCAL search_path = pg_catalog, pg_temp;

INSERT INTO bankwatch.customer_account (customer_ref, display_name)
VALUES
    ('C1001', '가상 고객 1'),
    ('C1002', '가상 고객 2'),
    ('C1003', '가상 고객 3')
ON CONFLICT (customer_ref) DO NOTHING;

INSERT INTO bankwatch.detection_rule (
    rule_code, rule_name, window_seconds, threshold_count
)
VALUES ('R02', '실패 후 신규 기기 로그인 성공', 600, 5)
ON CONFLICT (rule_code) DO NOTHING;

-- 충돌한 기존 행은 갱신하지 않는다. 다른 정책이면 신규 고객까지 롤백한다.
-- 이는 시드 실행 시의 정책 검사이며 DDL CHECK나 앱 권한 검사를 대신하지 않는다.
DO $check_seed_policy$
BEGIN
    IF EXISTS (
        SELECT 1 FROM bankwatch.detection_rule WHERE rule_code <> 'R02'
    ) OR NOT EXISTS (
        SELECT 1
        FROM bankwatch.detection_rule
        WHERE rule_code = 'R02'
          AND window_seconds = 600
          AND threshold_count = 5
    ) THEN
        RAISE EXCEPTION '고정 R02 정책(600초·5건)과 기존 규칙이 다릅니다.'
            USING ERRCODE = '55000';
    END IF;
END;
$check_seed_policy$;

-- 재실행해도 기존 ID·고객 이름·잠금 원인·규칙 이름을 변경하지 않는다.
-- 중복 INSERT 시 identity 번호가 소비될 수 있으므로 연속 ID를 가정하지 않는다.
COMMIT;
