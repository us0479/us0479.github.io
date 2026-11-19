# BankWatch 데이터 사전

| 항목 | 내용 |
|---|---|
| 팀명 | BankWatch |
| 이름 / 학번 | 이우석 / 2022112052 |
| 제출일 | 2026-11-29 — 사용자 지정 제출일, 실제 제출 여부는 별도 |
| 공식 문서 버전 | v1.10 |
| 검토일 | 2026-11-19 KST |
| 검토 근거 | 실제 Windows B1 빌드·B2 PostgreSQL/JAR HTTP 증거. 앱 main 신규 커밋·push와 실제 제출은 미실행 |

## 1. 적용 자료와 확인 범위

최신 사용자 전달 공지·교수 피드백 요약, 01·02·과제PPT·원본 양식과 현재 DDL/Java/ERD/시나리오/명세를 대조했다. 공지 원문 별첨은 없어 독립 열람했다고 주장하지 않는다. v2 실제 구현에서 개편을 시작했다. 1차마감2026-11-30 23:59, 최종2026-12-18 23:59 KST. 이번에는 모델 컬럼을 바꾸지 않고 문서/실행 증거를 갱신했다.

DDL SHA-256: `8e92b51ed257bcdf5bb57f0072669ac001628857791b81dd2871283b988bf883`

### 1.1 v2 기준선에서 재사용·교체한 구조

아래는 보존한 v2 원본과 현재 검증 소스의 차이다. 이번 문서 작업으로 SQL·Java를 바꿨다는 뜻은 아니다. 이전 파일별 단계에서 삭제한 구 사본·지원하지 않는 도구는 아래 정리 기록으로 구분한다.

| 테이블 | 재사용한 부분 | 현재 8개 테이블 구현에서 교체한 부분 |
|---|---|---|
| `operator_user` | 여섯 업무 컬럼·identity PK·login_id UQ·ADMIN/ANALYST·enabled true | v2 created_at 제외. 초기 운영자 생성은 별도 OperatorBootstrap |
| `customer_account` | 고객 식별·참조·이름·선택 잠금 원인 UQ/FK | updated_at 제외. 원인 사건의 고객 일치는 조치 서비스가 고객→사건 잠금 뒤 검사 |
| `security_event` | 발생/적재 시각·고객·기기·성공 신규 여부/실패 NULL | 원본 지정 양수 event_id·(occurred_at,event_id) PK·월별 RANGE. 구 batch/source/IP 컬럼 제외 |
| `detection_rule` | 규칙 ID·코드·명칭·구간/임계값 설명 | R02 하나·600초·5건. 구 버전/심각도/편집 컬럼 제외, 앱 SELECT 전용 |
| `alert` | 경보 PK·규칙/고객 FK·detected_at | run/window/anchor/dedup_key 등 구 컬럼 대신 bucket_start와 (rule_id,account_id,bucket_start) UQ |
| `alert_event` | 경보와 원본을 자식 행으로 연결 | event_occurred_at·is_anchor, 전체 복합 PK/FK, 경보당 대표 최대 1개 부분 UNIQUE |
| `incident` | 사건 PK·경보 UQ/FK·담당자·시각/판정/버전 | 저장 status 제외. 사건 유무/closed_at으로 상태 계산·종료 조합 CHECK |
| `incident_history` | 사건/실행자 FK·이력 PK·시각·사유 | action_type·reason varchar(2000), 네 행동 유형·공백 제외 CHECK. 구 NOTE/request/result 컬럼 제외 |

현재 DDL에는 `ingest_batch`·`detection_run`·`saved_filter`·`verification_run`이 없으며 현재 API는 R01·규칙 편집·저장 검색·검증 센터를 제공하지 않는다. 현재 Java의 WorkflowService·LabService는 제거됐다. 이전 단계에서 구 init/schema.sql·init/seed.sql·init/events.csv, 루트/화면/리소스의 구 events.csv 3개, scripts/generate-erd.py·generate-spec-data.py·run-verification.py까지 9개 파일을 삭제했다. 남은 _v2 문서·보고서는 보존 자료이며 현재 원본·시험 진입점으로 사용하지 않는다. 원본 ZIP과 이전 산출물은 보존했다.

| v2의 흐름 | 현재 재사용·교체 결과 |
|---|---|
| bw_import_events·WorkflowService.importCsv | EventService가 파싱·복합키 정렬·행별 고객 조회→INSERT·다음 고객 조회 실패 시 전체 롤백을 수행한다. SQL QRY-02~04는 현재 서비스에 있다. |
| Db.tx | Spring JDBC와 전체 업무 트랜잭션·제한 재시도 흐름을 유지하고 세션 actor는 Controller/Service가 검증한다. 임의 SQL 세션 변수로 운영자를 전달하지 않는다. |
| bw_run_detection | DetectionService의 고정 구간 윈도우 QRY-08, 경보/근거 저장 QRY-09·10, 실제 저장 근거 재검사 QRY-23으로 교체했다. |
| bw_claim_alert·bw_incident_action | 제한된 경보 잠금 함수와 IncidentClaimService, 고객→사건 잠금·역할/원인/버전 검사와 IncidentActionService로 분리했다. |
| 구 트리거·뷰 | 현재 DDL에 동일 트리거·뷰를 만들지 않는다. 원본 불변은 앱 권한, 교차 행 검사는 현재 서비스, 표시 상태는 조회 SQL이 담당한다. |

초기화 SQL의 원본은 `db/`다. 현재 `scripts/sync-assets.py`는 dist의 화면 3개와 루트 samples의 CSV 3개만 Spring 리소스로 복제하며 SQL을 복제·삭제하지 않는다. 앱은 `spring.sql.init.mode=never`로 init/ 사본을 실행하지 않는다. Maven·Dockerfile·CI에는 동기화 자동 실행이 없어 화면·샘플 반영 후 수동 동기화가 필요하다. 이전 단계에서 화면 3개·CSV 3개의 생성물 일치를 확인했고 최신 표준 JAR 리소스 7개도 대조했다. 이번 단계에서는 동기화를 실행하거나 사본을 수정하지 않는다.

## 2. 표기와 공통 규칙

- 이 절부터 8절까지의 테이블·DDL 보장은 현재 검증 소스의 db/schema.sql을 설명한다. v2 실제 기준선과의 차이는 1.1절에 있고, Windows 원본 적용/실행은 B2로 확인했고 앱 main 신규 커밋·push는 미실행이다.
- 모든 업무 테이블은 `bankwatch` 스키마에 있다. 논리 테이블은 8개이고 월별 자식 파티션 3개는 별도 업무 엔터티가 아니다.
- NULL 허용은 `customer_account.lock_incident_id`, `security_event.is_new_device`, `incident.closed_at`, `incident.resolution` 네 컬럼뿐이다. 나머지 40개 컬럼은 NULL 불가다.
- 기본값 `없음`은 명시한 DEFAULT가 없다는 뜻이다. NULL 허용 컬럼을 생략하면 NULL이 되지만, DEFAULT NULL을 선언한 것은 아니다.
- 여섯 ID의 `GENERATED ALWAYS AS IDENTITY`는 자동 생성 속성이다. 일반 INSERT에서 해당 컬럼을 생략한다. 원본 `event_id`는 자동 생성하지 않는다. 시퀀스 값의 공백은 오류 판정 기준이 아니다.
- `pg_catalog.clock_timestamp()`는 기본값을 평가하는 시점의 DB 서버 시각이다. 원본 발생 시각이나 트랜잭션 시작 시각을 대신하지 않는다. 모든 시각 컬럼은 `timestamptz`다.
- 후보키는 전체 행에서 NULL 없이 유일한 최소 식별자를 뜻한다. NULL 허용 UNIQUE와 부분 UNIQUE를 전체 테이블의 후보키로 세지 않는다.
- FK 10개는 모두 `ON DELETE NO ACTION`, `ON UPDATE NO ACTION`이며 기본값인 `NOT DEFERRABLE`로 검사한다. 참조 중인 부모 키의 삭제·변경을 허용하지 않고 자동 연쇄 삭제·갱신을 하지 않는다. 비키 컬럼의 갱신까지 금지하는 정책은 아니다.

## 3. 테이블별 데이터 사전

### 3.1 `operator_user` — 관제 화면 운영자

ADMIN·ANALYST의 인증 식별자, 비밀번호 해시와 활성 여부를 보관한다. 고객의 모바일뱅킹 로그인 계정이 아니다. 후보키는 `(operator_id)`, `(login_id)`다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `operator_id` | `bigint` | 불가 | `GENERATED ALWAYS AS IDENTITY` | PK. 운영자 내부 식별자 |
| `login_id` | `varchar(50)` | 불가 | 없음 | UQ. 관제 화면 로그인 ID |
| `password_hash` | `text` | 불가 | 없음 | 운영자 비밀번호 해시. BCrypt 생성·검증은 앱 책임 |
| `display_name` | `varchar(80)` | 불가 | 없음 | 운영자 표시 이름 |
| `role` | `text` | 불가 | 없음 | ADMIN 또는 ANALYST |
| `enabled` | `boolean` | 불가 | `true` | 운영자 인증 허용 여부. 고객 가입·접근 상태와 무관 |

| 제약명 | 내용 |
|---|---|
| `pk_operator_user` | PK `(operator_id)` |
| `uq_operator_user_login_id` | UNIQUE `(login_id)` |
| `ck_operator_user_role` | CHECK `role IN ('ADMIN', 'ANALYST')` |

비밀번호 해시 형식, 활성 운영자 여부, 요청별 권한과 이력 실행자의 세션 일치는 DDL이 검사하지 않는다. 참조되는 관계는 F07·F09다.

### 3.2 `customer_account` — 가상 분석 대상 고객

인증 로그가 지칭하는 고객과 현재 보호 잠금의 원인 사건을 보관한다. 후보키는 `(account_id)`, `(customer_ref)`다. NULL 허용 `lock_incident_id`의 UNIQUE는 전체 고객의 후보키가 아니다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `account_id` | `bigint` | 불가 | `GENERATED ALWAYS AS IDENTITY` | PK. 가상 고객 내부 식별자 |
| `customer_ref` | `varchar(40)` | 불가 | 없음 | UQ. CSV의 가상 고객 참조값. 예: C1001 |
| `display_name` | `varchar(80)` | 불가 | 없음 | 가상 고객 표시 이름 |
| `lock_incident_id` | `bigint` | 허용 | 없음 | UQ·FK F10. 현재 보호 잠금의 원인 사건. NULL이면 접근 가능 |

| 제약명 | 내용 |
|---|---|
| `pk_customer_account` | PK `(account_id)` |
| `uq_customer_account_customer_ref` | UNIQUE `(customer_ref)` |
| `uq_customer_account_lock_incident` | UNIQUE `(lock_incident_id)`; NULL은 여러 행에 허용 |
| `fk_customer_account_lock_incident` | F10. `incident` 생성 뒤 ALTER TABLE로 추가해 순환 참조를 완성 |

별도 CHECK는 없다. FK는 사건의 존재만 검사하며 그 사건이 같은 고객의 경보에 속하는지는 서비스가 확인한다. 참조되는 다른 관계는 F01·F02다.

### 3.3 `security_event` — 가상 인증 서버의 확정 결과 원본

성공·실패가 확정된 인증 결과를 CSV로 수집한다. 후보키와 PK는 `(occurred_at, event_id)` 하나다. `event_id` 단독 유일성을 가정하지 않는다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `occurred_at` | `timestamptz` | 불가 | 없음 | PK 첫 컬럼·파티션 키. 가상 서버의 이벤트 발생 시각 |
| `event_id` | `bigint` | 불가 | 없음 | PK 두 번째 컬럼. 원본이 지정한 양의 이벤트 ID |
| `account_id` | `bigint` | 불가 | 없음 | FK F01. 이벤트의 가상 고객 |
| `device_ref` | `varchar(100)` | 불가 | 없음 | 가상 서버가 제공한 기기 참조값 |
| `event_type` | `text` | 불가 | 없음 | LOGIN_FAILED 또는 LOGIN_SUCCESS |
| `is_new_device` | `boolean` | 허용 | 없음 | 성공 당시 원본 시스템이 판정한 신규 기기 여부. 실패에서는 NULL |
| `ingested_at` | `timestamptz` | 불가 | `pg_catalog.clock_timestamp()` | BankWatch가 원본을 적재한 시각. 재입력 내용 비교에서는 제외 |

| 제약명 | 내용 |
|---|---|
| `pk_security_event` | PK `(occurred_at, event_id)`; 월별 자식에 대응 PK가 생성됨 |
| `fk_security_event_account` | F01. 고객 필수 참조 |
| `ck_security_event_positive_id` | CHECK `event_id > 0` |
| `ck_security_event_type` | CHECK `event_type IN ('LOGIN_FAILED', 'LOGIN_SUCCESS')` |
| `ck_security_event_new_device` | CHECK `(event_type = 'LOGIN_SUCCESS' AND is_new_device IS NOT NULL) OR (event_type = 'LOGIN_FAILED' AND is_new_device IS NULL)` |

참조되는 관계는 F05다. UTF-8, 파일 크기·행 수, 오프셋 포함 시각 형식, 중복 내용 비교와 파일 전체 롤백은 서비스 책임이다. 입력 예는 5절에 정리한다.

### 3.4 `detection_rule` — 고정 탐지 정책 설명

이번 범위의 R02 정책을 식별한다. 후보키는 `(rule_id)`, `(rule_code)`다. 규칙 편집·버전 관리는 현재 범위에 없다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `rule_id` | `bigint` | 불가 | `GENERATED ALWAYS AS IDENTITY` | PK. 규칙 내부 식별자 |
| `rule_code` | `text` | 불가 | 없음 | UQ. 고정 시드 정책의 코드 R02 |
| `rule_name` | `text` | 불가 | 없음 | 규칙 표시 이름. 초기 시드 문구는 실패 후 신규 기기 로그인 성공이며 재시드는 기존 이름을 유지 |
| `window_seconds` | `smallint` | 불가 | 없음 | 고정 정책 설명값 600초 |
| `threshold_count` | `smallint` | 불가 | 없음 | 고정 정책 설명값 실패 5건 |

| 제약명 | 내용 |
|---|---|
| `pk_detection_rule` | PK `(rule_id)` |
| `uq_detection_rule_code` | UNIQUE `(rule_code)` |

R02·600·5는 DEFAULT나 CHECK가 아니다. DDL만으로 다른 코드·음수·다른 정책값을 차단하지 않는다. `db/seed.sql`의 단일 R02·600·5 정책 검사, DetectionService의 고정 규칙 확인, 현재 앱 SELECT 전용 권한으로 통제한다. 참조되는 관계는 F03이다.

### 3.5 `alert` — 고객·고정 구간별 탐지 경보

같은 규칙·고객·고정 10분 구간에서 최초 커밋한 탐지 사실을 보관한다. 후보키는 `(alert_id)`, `(rule_id, account_id, bucket_start)`다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `alert_id` | `bigint` | 불가 | `GENERATED ALWAYS AS IDENTITY` | PK. 경보 식별자 |
| `rule_id` | `bigint` | 불가 | 없음 | FK F03·복합 UQ 첫 컬럼. 적용 규칙 |
| `account_id` | `bigint` | 불가 | 없음 | FK F02·복합 UQ 두 번째 컬럼. 경보 대상 고객 |
| `bucket_start` | `timestamptz` | 불가 | 없음 | 복합 UQ 세 번째 컬럼. 고정 탐지 구간의 포함 하한 |
| `detected_at` | `timestamptz` | 불가 | `pg_catalog.clock_timestamp()` | 경보 저장 시각. 원본의 발생 시각과 구별 |

| 제약명 | 내용 |
|---|---|
| `pk_alert` | PK `(alert_id)` |
| `fk_alert_rule` | F03. 규칙 필수 참조 |
| `fk_alert_account` | F02. 고객 필수 참조 |
| `uq_alert_rule_account_bucket` | UNIQUE `(rule_id, account_id, bucket_start)` |

별도 CHECK는 없다. `bucket_start`의 10분 정렬, R02 적격성, 근거 완성은 서비스가 검사한다. 구간 끝과 사건 상태 문자열은 저장하지 않는다. 참조되는 관계는 F04·F06이다.

### 3.6 `alert_event` — 경보와 원본 근거의 연결

대표 성공과 그보다 앞선 실패를 원본 전체 키로 연결한다. 선언된 후보키와 PK는 `(alert_id, event_occurred_at, event_id)`다. 원본 키만으로는 DDL상 여러 경보의 연결을 구별할 수 없다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `alert_id` | `bigint` | 불가 | 없음 | PK 첫 컬럼·FK F04. 근거를 보존할 경보 |
| `event_occurred_at` | `timestamptz` | 불가 | 없음 | PK 두 번째 컬럼·FK F05 첫 컬럼. 원본 occurred_at |
| `event_id` | `bigint` | 불가 | 없음 | PK 세 번째 컬럼·FK F05 두 번째 컬럼. 원본 event_id |
| `is_anchor` | `boolean` | 불가 | 없음 | 해당 연결이 대표 성공이면 true, 선행 실패 근거이면 false |

| 제약명/인덱스명 | 내용 |
|---|---|
| `pk_alert_event` | PK `(alert_id, event_occurred_at, event_id)` |
| `fk_alert_event_alert` | F04. 경보 필수 참조 |
| `fk_alert_event_source` | F05. `(event_occurred_at, event_id)` → `security_event(occurred_at, event_id)`인 복합 FK 한 개 |
| `uq_alert_event_anchor` | 부분 UNIQUE 인덱스 `(alert_id) WHERE is_anchor`; 경보당 true인 행 최대 1개 |

별도 CHECK는 없다. 부분 UNIQUE는 전체 테이블의 후보키가 아니며 대표의 존재·성공 유형·신규 여부를 검사하지 않는다. 같은 고객·구간, 엄격히 선행하는 실패, 대표 정확히 1개·실패 5개 이상은 같은 탐지 트랜잭션에서 검사한다. DetectionService는 QRY-23으로 실제 저장 근거를 경보·원본 전체 키에 다시 JOIN하고 total·대표 1·예상 실패 수가 맞는지 확인하며 불일치 시 앞선 구간까지 전체 롤백한다. `is_anchor`의 정규화 쟁점은 7절에 남긴다.

### 3.7 `incident` — 담당자가 선점한 사건

경보의 담당자, 종료 사실, 변경 버전을 보관한다. 후보키는 `(incident_id)`, `(alert_id)`다. 미배정 사건 행을 미리 만들지 않고 선점 때 행을 생성한다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `incident_id` | `bigint` | 불가 | `GENERATED ALWAYS AS IDENTITY` | PK. 사건 식별자 |
| `alert_id` | `bigint` | 불가 | 없음 | UQ·FK F06. 사건의 유일한 경보 |
| `assignee_id` | `bigint` | 불가 | 없음 | FK F07. 선점한 담당 운영자 |
| `created_at` | `timestamptz` | 불가 | `pg_catalog.clock_timestamp()` | 사건 생성 시각 |
| `closed_at` | `timestamptz` | 허용 | 없음 | NULL이면 조사 중, 값이 있으면 종료 |
| `resolution` | `text` | 허용 | 없음 | 종료 판정. CONFIRMED/FALSE_POSITIVE/INCONCLUSIVE |
| `version` | `integer` | 불가 | `0` | 오래된 화면·동시 갱신을 판별하는 버전 |

| 제약명 | 내용 |
|---|---|
| `pk_incident` | PK `(incident_id)` |
| `uq_incident_alert` | UNIQUE `(alert_id)`; 같은 경보의 사건 최대 1개 |
| `fk_incident_alert` | F06. 경보 필수 참조 |
| `fk_incident_assignee` | F07. 운영자 필수 참조 |
| `ck_incident_version` | CHECK `version >= 0` |
| `ck_incident_closure_pair` | CHECK `(closed_at IS NULL) = (resolution IS NULL)` |
| `ck_incident_closed_time` | CHECK `closed_at IS NULL OR closed_at >= created_at` |
| `ck_incident_resolution` | CHECK `resolution IS NULL OR resolution IN ('CONFIRMED', 'FALSE_POSITIVE', 'INCONCLUSIVE')` |

판정 의미는 CONFIRMED=모의 조사상 이상 접속 확인, FALSE_POSITIVE=정상 접속으로 판단한 오탐, INCONCLUSIVE=판단 유보다. FK는 담당자의 ANALYST·활성 여부를 검사하지 않는다. `version >= 0`만으로 자동 증가·기대 버전 일치를 보장하지 않는다. 종료는 계정 보호를 자동 해제하지 않는다. 참조되는 관계는 F08·F10이다.

### 3.8 `incident_history` — 사건 행동의 실행자·사유 이력

배정·잠금·해제·종료의 실행자를 기록한다. 후보키와 PK는 `(history_id)`다. `(incident_id, action_type)`에 UNIQUE는 없다.

| 컬럼 | 자료형 | NULL 허용 | 기본값/자동 생성 | 키·업무 의미 |
|---|---|---|---|---|
| `history_id` | `bigint` | 불가 | `GENERATED ALWAYS AS IDENTITY` | PK. 이력 식별자 |
| `incident_id` | `bigint` | 불가 | 없음 | FK F08. 행동 대상 사건 |
| `actor_id` | `bigint` | 불가 | 없음 | FK F09. 실제 요청 세션의 운영자 |
| `action_type` | `text` | 불가 | 없음 | CLAIM/LOCK_ACCOUNT/UNLOCK_ACCOUNT/CLOSE |
| `reason` | `varchar(2000)` | 불가 | 없음 | 공백 문자만으로 구성할 수 없는 사유. 최대 2,000문자 |
| `created_at` | `timestamptz` | 불가 | `pg_catalog.clock_timestamp()` | 행동 이력 저장 시각 |

| 제약명 | 내용 |
|---|---|
| `pk_incident_history` | PK `(history_id)` |
| `fk_incident_history_incident` | F08. 사건 필수 참조 |
| `fk_incident_history_actor` | F09. 운영자 필수 참조 |
| `ck_incident_history_action` | CHECK `action_type IN ('CLAIM', 'LOCK_ACCOUNT', 'UNLOCK_ACCOUNT', 'CLOSE')` |
| `ck_incident_history_reason` | CHECK `reason ~ '[^[:space:]]'`; 공백·탭·개행만 있는 사유 거부 |

CLAIM 사유는 현재 앱의 고정 문구 `사건 담당자로 배정`이다. `actor_id`를 세션에서 얻는 것, 행동 권한, 상태 변경·이력의 원자성은 서비스 책임이다. 현재 grant_runtime.sql은 앱에 이력 INSERT만 허용하고 UPDATE/DELETE는 허용하지 않는다.

## 4. FK 관계·카디널리티·선택성

각 행의 수는 DB가 허용하는 구조적 범위다. 부모가 존재해도 자식이 반드시 생기는 것은 아니다. 삭제/수정은 각각 ON DELETE/ON UPDATE 정책이다.

| ERD ID | 실제 FK 제약명·자식 컬럼 | 참조 대상 | 자식 1행의 부모 수 | 부모 1행의 자식 수 | 삭제/수정 |
|---|---|---|---|---|---|
| F01 | `fk_security_event_account`: `security_event.account_id` | `customer_account(account_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F02 | `fk_alert_account`: `alert.account_id` | `customer_account(account_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F03 | `fk_alert_rule`: `alert.rule_id` | `detection_rule(rule_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F04 | `fk_alert_event_alert`: `alert_event.alert_id` | `alert(alert_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F05 | `fk_alert_event_source`: `alert_event(event_occurred_at, event_id)` | `security_event(occurred_at, event_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F06 | `fk_incident_alert`: `incident.alert_id` | `alert(alert_id)` | 1, 필수 | 0..1 | NO ACTION / NO ACTION |
| F07 | `fk_incident_assignee`: `incident.assignee_id` | `operator_user(operator_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F08 | `fk_incident_history_incident`: `incident_history.incident_id` | `incident(incident_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F09 | `fk_incident_history_actor`: `incident_history.actor_id` | `operator_user(operator_id)` | 1, 필수 | 0..N | NO ACTION / NO ACTION |
| F10 | `fk_customer_account_lock_incident`: `customer_account.lock_incident_id` | `incident(incident_id)` | 0..1, 선택 | 0..1 | NO ACTION / NO ACTION |

경보와 원본은 `alert_event`를 통해 구조상 M:N이다. F06의 자식 최대 1은 사건의 경보 UNIQUE, F10의 양방향 선택 하나는 NULL 허용 FK와 원인 사건 UNIQUE에서 나온다. F10은 그 사건이 같은 고객에 속한다는 뜻까지 보장하지 않는다.
경보당 근거 6개 이상·대표 정확히 1개, 선점당 CLAIM 정확히 1개는 FK의 최소 건수가 아니라 서비스 커밋 조건이다.

## 5. 원본 키·중복 경보 키·월별 저장·R02

### 5.1 키의 서로 다른 역할

| 목적 | 키와 처리 기준 |
|---|---|
| 원본 재전송 식별 | `security_event(occurred_at, event_id)` PK. 같은 ID도 시각이 다르면 별개 키다. `2026-11-02 10:05:00+09:00`과 `2026-11-02 01:05:00+00:00`는 같은 순간이므로 같은 ID와 조합하면 중복 키다. |
| 동일 원본의 내용 비교 | 서비스가 정규화한 고객·기기·유형·신규 여부 등을 비교한다. 동일 키·동일 내용은 건너뛰고, 다른 내용은 파일 전체 신규 입력을 롤백한다. PK만으로 내용 일치까지 보장하지 않는다. |
| 경보 중복 방지 | `alert(rule_id, account_id, bucket_start)` UQ. 여러 적격 성공이 있어도 같은 구간의 경보는 하나다. 원본 로그를 합치는 키가 아니다. |
| 근거 연결 | `alert_event` PK와 F05의 전체 복합 FK. 시각을 빠뜨린 `event_id` 단독 JOIN/FK를 사용하지 않는다. |

CSV 입력은 UTF-8·최대 2 MiB·5,000개 데이터 행이다. 헤더 순서는 `event_id,customer_ref,occurred_at,device_ref,event_type,is_new_device`이며, 시각은 UTC 오프셋 포함 ISO 8601·소수 초 최대 6자리다. 성공의 신규 여부는 true/false, 실패는 빈 필드로 받아 NULL로 저장한다. 이 형식·용량 검사는 EventService 책임이며 DDL의 NULL/CHECK와 구분한다.

### 5.2 물리 파티션 — 발생 시각의 KST 월별 RANGE

부모는 `PARTITION BY RANGE (occurred_at)`다. 모든 범위는 `[포함 하한, 제외 상한)`이고 DEFAULT 파티션은 없다.

| 물리 자식 파티션 | 포함 하한 KST | 제외 상한 KST |
|---|---|---|
| `security_event_2026_10` | `2026-10-01 00:00:00+09:00` | `2026-11-01 00:00:00+09:00` |
| `security_event_2026_11` | `2026-11-01 00:00:00+09:00` | `2026-12-01 00:00:00+09:00` |
| `security_event_2026_12` | `2026-12-01 00:00:00+09:00` | `2027-01-01 00:00:00+09:00` |

예를 들어 `2026-11-01 00:00:00+09:00`은 11월 파티션이고, UTC로는 `2026-10-31 15:00:00+00:00`이다. 세션 표시 시간대가 달라도 같은 순간은 같은 파티션으로 간다. 준비된 월 밖의 INSERT는 거부되며 서비스가 파일 전체를 롤백한다. 참조 중인 원본의 보존·파티션 폐기는 별도 정책 검토 대상이다.

### 5.3 논리 탐지 — 고정 10분 구간

R02의 구간은 월별 물리 저장 범위와 다르다. 계약식은 `date_bin(INTERVAL '10 minutes', occurred_at, TIMESTAMPTZ '2000-01-01 00:00:00+09:00')`이며 구간 끝은 시작+600초로 계산한다. 현재 DetectionService의 실제 QRY-08에 사용되며, 식 자체의 설명과 기존 실행 기록은 구분한다.
QRY-08은 성공·실패를 함께 유지한 `PARTITION BY account_id,bucket_start ORDER BY occurred_at RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE GROUP`에서 실패를 센다. 같은 고객·구간의 신규 기기 성공 전에 실패 5개 이상이면 후보이며, 같은 시각의 실패는 ID가 작아도 제외한다. 후보를 고른 뒤 ROW_NUMBER의 발생 시각·이벤트 ID 오름차순 첫 행을 대표로 삼고 모든 적격 선행 실패와 연결한다.

| 이벤트 유형 | `is_new_device` | DDL 허용 | 업무 의미 |
|---|---|---|---|
| LOGIN_SUCCESS | true | 허용 | 신규 기기 성공. 선행 실패 등 나머지 조건을 만족해야 R02 후보 |
| LOGIN_SUCCESS | false | 허용 | 기존 기기 성공. R02 후보에서 제외 |
| LOGIN_SUCCESS | NULL | 거부 | 성공 당시 신규 여부 누락 |
| LOGIN_FAILED | NULL | 허용 | 실패에는 신규 기기 판정값을 저장하지 않음 |
| LOGIN_FAILED | true 또는 false | 거부 | 실패의 NULL 규칙 위반 |

정상 샘플은 2026-11-02 KST C1001의 실패 ID 1~5, 신규 성공 ID 6·7이다. 기대값은 후보 2·구간 1·대표 성공 ID 6·신규 경보 1·근거 6, 재탐지 신규 경보 0·누적 근거 6이다. 최신 새 DB JUnit과 동일 표준 JAR의 HTTP 결과가 이 기대값과 일치하며 TEST-04에 연결한다. 과거 E-CI·E-DETECT도 같은 기대값의 별도 실행이다. 재탐지는 최초 경보·근거 fingerprint를 보존한다. 최신 DB의 별도 TEST-05는 events-boundaries.csv의 7종·41건으로 후보·경보·근거 0을 확인했다. 동시 탐지용 다른 픽스처의 전체 누적값과 이 정상 7건 결과를 합치지 않는다(10절).

## 6. 등록·배정·잠금·종료의 계산 상태

| 표시할 사실/상태 | 계산 원천 | 주의점 |
|---|---|---|
| 등록된 분석 대상 | `customer_account` 행 존재 | 실제 은행의 가입 상태·가입일·탈퇴 상태 컬럼은 없다. 고객 행의 존재를 실은행 가입 완료로 해석하지 않는다. |
| 운영자 활성 | `operator_user.enabled` | 관제 화면 인증 조건이다. 고객 가입이나 고객 보호 잠금과 구별한다. |
| 미배정 | 경보에 연결된 `incident` 행 없음 | LEFT JOIN 결과에서 사건 ID 부재를 먼저 검사한다. |
| 조사 중 | 사건 존재 + `incident.closed_at IS NULL` | 담당자는 assignee_id다. DDL은 담당자의 ANALYST 여부까지 검사하지 않는다. |
| 종료 | 사건 존재 + `incident.closed_at IS NOT NULL` | resolution도 함께 값이 있어야 한다. 재오픈은 현재 범위 밖이다. |
| 가상 계정 접근 가능 | `customer_account.lock_incident_id IS NULL` | 신규 고객에서 이 NULL 허용 컬럼을 생략하면 NULL이 된다. 명시적 DEFAULT NULL 선언은 아니다. |
| 가상 계정 보호 잠금 | `customer_account.lock_incident_id IS NOT NULL` | 현재 원인 사건을 FK로 추적한다. 종료된 사건이 원인으로 남을 수도 있다. |

정상 전이는 ANALYST 선점 시 사건·CLAIM 생성, 담당 ANALYST의 조사 중 보호 잠금, ADMIN의 현재 원인 확인 후 해제, 담당 ANALYST의 조사 종료다. 역할·기대 버전·사유를 검사하고 변경과 이력을 함께 커밋한다. 종료만으로 원인을 NULL로 바꾸지 않으며 종료한 원인 사건에서도 ADMIN 해제가 가능하다. 실패 시 해당 업무의 상태·버전·이력 변경을 함께 롤백한다.

## 7. 함수 종속과 3NF 검토

모든 컬럼은 단일 값을 저장하고 근거·이력 목록은 자식 행으로 분리했다. FK의 존재만으로 3NF를 증명하지 않고 실제 속성의 함수 종속을 검토한다.

| 테이블 | 주요 함수 종속과 검토 |
|---|---|
| `operator_user` | operator_id 또는 login_id → 나머지 속성. 선언된 키 외에 비키 속성이 다른 비키 속성을 결정하는 업무 종속은 없다. |
| `customer_account` | account_id 또는 customer_ref → 나머지 속성. 고객 이름은 여기에서만 관리한다. NULL 허용 lock_incident_id를 전체 후보키로 주장하지 않는다. |
| `security_event` | (occurred_at, event_id) → account_id, device_ref, event_type, is_new_device, ingested_at. ID 단독이 아니라 전체 원본 키로 사건 사실을 결정한다. 동일 기기의 여러 로그를 기기별 단일 행으로 오해하지 않는다. |
| `detection_rule` | rule_id 또는 rule_code → rule_name, window_seconds, threshold_count. R02·600·5는 시드 정책이며 현재 몇 행인지로 함수 종속을 추정하지 않는다. |
| `alert` | alert_id 또는 (rule_id, account_id, bucket_start) → 나머지 속성. 규칙 이름·고객 이름·구간 끝을 복사하지 않는다. |
| `alert_event` | 선언된 전체 연결키 (alert_id, event_occurred_at, event_id) → is_anchor. 아래 R02 업무 종속의 의도적 중복 예외를 함께 검토한다. |
| `incident` | incident_id 또는 alert_id → 담당자·생성/종료 시각·판정·버전. 담당자 이름이나 경보 고객 ID를 다시 저장하지 않는다. |
| `incident_history` | history_id → 사건·실행자·행동·사유·시각. 실행자 이름·역할은 복사하지 않는다. 한 사건에 여러 행동이 가능하므로 incident_id만으로 이력을 결정하지 않는다. |

`is_anchor`는 02에서 특정 경보와 특정 원본의 연결 역할로 정의한 속성이다. DDL은 같은 원본을 여러 경보에 연결할 수 있으며, 원본 키만으로 is_anchor가 같아야 한다는 제약은 없다. `(alert_id) WHERE is_anchor`의 부분 UNIQUE도 true인 부분집합의 제약이므로 전체 연결키의 종속을 증명하지 않는다.

그러나 현재 R02 서비스 계약은 연결된 성공을 대표(true), 연결된 실패를 비대표(false)로 한정한다. 이 업무 범위에서는 원본 복합키 → event_type → is_anchor로 값을 유도할 수 있다. 따라서 전체 연결키에 대한 종속만 제시하며 부분 종속·파생 속성의 중복을 검토하지 않고 2NF·3NF 통과라고 단정할 수 없다.

**개편 설계에서는 02의 is_anchor와 부분 UNIQUE를 유지하고, 경보당 대표 최대 1개를 선언적으로 제한하기 위한 파생 표식의 의도적 중복으로 기록한다.** 잘못된 유형의 원본을 대표로 표시할 수 있다는 위험은 현재 앱의 원본 UPDATE/DELETE 금지와 같은 탐지 트랜잭션의 QRY-23 검사로 통제한다. E-DETECT에는 잘못 저장된 대표·근거를 주입했을 때 앞선 구간을 포함한 탐지 전체가 롤백된 기록이 있다. 관련 소스·적용 SQL 해시가 현재와 일치하며 이번 문서 단계의 새 실행은 아니다. 이 예외를 독립적인 관계 속성이라는 설명만으로 감추거나 8개 테이블 모두의 엄격한 3NF 통과를 주장하지 않는다.

나머지 7개 테이블은 위에서 확인한 함수 종속 범위에서 3NF에 부합한다. 고객·운영자·규칙 명칭은 각각 원본 테이블에서 JOIN으로 얻어 명칭 변경 시 갱신 불일치를 줄인다. 따라서 현재 조회 이름은 변경 후 이름일 수 있으며, 이름의 과거 스냅샷을 보존하는 모델은 아니다.

## 8. DDL 보장과 서비스 트랜잭션의 경계

| 항목 | 현재 DDL이 보장하는 조건 | 현재 서비스·권한이 검사하는 조건 |
|---|---|---|
| 식별·참조 | PK 8개·FK 10개·일반 UQ 6개·필수 컬럼·자료형 | EventService의 CSV customer_ref 해석·복합키 정렬·ON CONFLICT 뒤 정규화 내용 비교·파일 신규 입력 전체 롤백 |
| 행 값 | CHECK 10개: 역할 1·원본 3·사건 4·이력 2 | BCrypt·활성 역할·유효한 입력·사유 검사. NOT NULL만으로 비공백 이름·해시 형식을 보장하지 않음 |
| R02 | 경보 중복 키·근거 전체 복합 FK·대표 최대 1개 | REPEATABLE READ에서 QRY-07 고정 정책·QRY-08 후보/대표, QRY-09·10 신규 경보/근거 저장, QRY-23 실제 저장 근거 검사 후 함께 커밋 |
| 재탐지 | 경보 UNIQUE로 중복 INSERT 차단 | 지정 경보 키의 DO NOTHING RETURNING, 신규 경보에만 근거 저장. 최초 근거·대표 불변 |
| 선점 | 경보당 사건 최대 1개·담당 운영자 참조 | 활성 ANALYST→제한 함수 잠금→역할 재검사→같은 연결의 별도 SELECT→사건·CLAIM 함께 저장. 이미 선점은 409 |
| 조치 | 원인 사건 FK/UQ·종료 조합·판정·비음수 버전 | 고객→사건 잠금 뒤 역할·담당자·관계·원인 고객·상태·기대 버전·사유 재검사. 원인/버전/이력 원자 저장 |
| 종료·해제 | 종료 시각/판정 NULL 조합·시각 순서 | 종료는 원인 유지. ADMIN이 현재 원인 사건에서만 해제하며 종료 뒤에도 허용. 종료 갱신·CLOSE를 함께 저장 |
| 중단·교착 | 커밋 전 연결 종료는 PostgreSQL이 미커밋 변경 롤백 | Db.tx가 전체 업무를 묶고 40P01/40001만 바깥의 새 트랜잭션으로 최대 2회 재시도. 일반 입력/권한/버전 오류·연결 종료는 자동 재시도하지 않음 |
| 불변 기록·규칙 | 스키마 PUBLIC·잠금 함수 PUBLIC EXECUTE 해제 | 현재 grant_runtime.sql이 규칙 SELECT 전용·불변 기록 UPDATE/DELETE 금지·지정 컬럼 UPDATE·제한 함수 EXECUTE 부여 |

현재 DDL 함수는 `bankwatch.lock_alert_for_claim(p_alert_id bigint) RETURNS boolean`이다. 소유자의 SECURITY DEFINER·VOLATILE·고정 `search_path = pg_catalog, pg_temp`·스키마 지정 `bankwatch.alert`를 사용한다. 요청한 경보 한 행만 FOR UPDATE하고 존재하면 true, 없거나 인자가 NULL이면 false를 반환한다. 인증·사건 생성·커밋을 하지 않으며 잠금은 호출 트랜잭션 종료까지 유지된다. 선언~종결 15줄로 200줄 이하다.
PUBLIC EXECUTE는 해제돼 있고 현재 grant_runtime.sql이 bankwatch_app에 EXECUTE를 부여한다. 앱에 경보 UPDATE를 추가하지 않는다. v2의 bw_claim_alert와 같은 함수가 아니며 실제 private 체크아웃·현재 상시 DB의 설치 여부는 미확인이다.

### 8.1 현재 앱 DB 역할의 권한 범위

| 대상 | bankwatch_app에 부여한 권한 |
|---|---|
| operator_user | SELECT. 앱은 운영자 생성·비밀번호·역할·활성 상태를 갱신하지 않음 |
| customer_account | SELECT, UPDATE(lock_incident_id). 고객 INSERT·이름/참조 변경 권한 없음 |
| security_event | SELECT, INSERT(occurred_at,event_id,account_id,device_ref,event_type,is_new_device). 원본 PK 시각·ID는 입력, ingested_at은 DB 생성 |
| detection_rule | SELECT만. 고정 시드·정책 확인 외 규칙 변경 기능 없음 |
| alert | SELECT, INSERT(rule_id,account_id,bucket_start). 선점 잠금은 제한 함수 EXECUTE로 수행 |
| alert_event | SELECT, INSERT(alert_id,event_occurred_at,event_id,is_anchor) |
| incident | SELECT, INSERT(alert_id,assignee_id), UPDATE(closed_at,resolution,version) |
| incident_history | SELECT, INSERT(incident_id,actor_id,action_type,reason) |
| 함수·시퀀스 | lock_alert_for_claim(bigint) EXECUTE. alert·incident·incident_history identity 시퀀스 USAGE |

모든 업무 테이블의 DELETE/TRUNCATE, 원본·경보·근거·이력 UPDATE, 규칙·운영자 변경, 자식 파티션 직접 접근 권한은 주지 않는다. 앱 역할은 소유자·슈퍼유저·역할 멤버십이 없는 전용 LOGIN이어야 한다. DB 공통 역할 bankwatch_app과 운영자 ADMIN/ANALYST는 다른 구분이며 세션별 업무 권한은 서비스가 확인한다. SC-04의 pg_signal_backend는 별도 검증 역할만 사용한다.

### 8.2 원본·초기화 순서

현재 초기화는 소유자 연결의 `db/schema.sql` → `db/seed.sql` → `db/grant_runtime.sql` → 일회성 `kr.ac.bankwatch.OperatorBootstrap` → 앱 역할 웹 기동 순서다. 앱 역할은 grant 전에 따로 준비한다. 기초 시드는 가상 고객 C1001~C1003과 고정 R02만 만들며 운영자·비밀번호·로그를 넣지 않는다.
OperatorBootstrap은 로컬 환경에서 받은 admin·analyst_a·analyst_b 초기 비밀번호를 BCrypt로 해시해 한 트랜잭션에서 생성한다. 일치하는 기존 운영자의 해시는 유지하고 역할/활성 불일치·필수 설정 누락은 중단한다. 초기화 소유자 변수와 운영자 초기 비밀번호를 일반 앱에 전달하지 않는다.
Windows cmd 재현 명령과 별도 bankwatch_test_* DB의 환경 입력은 현재 README·시나리오 Windows 초기화 절을 따른다. Windows B1 표준 빌드·B2 PostgreSQL16.16/JAR HTTP 실행은 확인했다. 전체 Compose app/bootstrap 기동은 미실행이다. Compose .env가 Java 프로세스에 자동 전달된다고 가정하지 않는다. SQL 원본은 db/이며 현재 앱 자동 SQL 초기화와 sync 도구의 SQL 복제는 사용하지 않는다.

## 9. SC-01~SC-04의 데이터 연결

| SC | 조회 | 변경·실패 후 상태 | 관련 TEST |
|---|---|---|---|
| SC01 | 운영자·고객·원본·규칙·경보·근거·사건·이력 | 원본7→경보1/근거6→사건1/조치이력4·version3. 각 요청별 원자 커밋 | TEST01/02/04/07 |
| SC02 | 운영자·경보·고객·사건·이력 | 서로 다른 담당자 선점200/409, 승자 두로그인 보호200/409. 각 COMMIT1/ROLLBACK1. 구버전8테이블 불변 | TEST06 |
| SC03 | 운영자·고객·원본 | INSERT8 성공 관찰 뒤 C9999 조회0행으로400/전체롤백. INSERT9 미실행·원본7/8테이블 지문복원. 직접FK는 TEST09 | TEST03 |
| SC04 | 운영자·경보·고객·사건·이력 | 원인/version UPDATE 뒤 이력전 실제PID232 종료503. 전체복원·자동재시도0·명시적 새요청 성공 | TEST08 |

## 10. 실제 Windows 실행 대조

실제 Windows 실행은 **2026-11-19 16:33:35~16:34:20 KST**다. Python3.12.10·Temurin21.0.12.1·Wrapper3.9.16·Boot4.1.2·Docker Linux PostgreSQL16.16을 사용했다.
B1 validate/clean package 성공(표준 PostgreSQL 시험0), B2 `-Ppostgres-it verify`의 JUnit10/실패0/오류0/건너뜀0, 실제 JAR HTTP48요청/132검사 모두 통과했다. 시험·요청·검사 수를 합산하지 않는다.

| 근거 | 식별 |
|---|---|
| B2 ZIP | BankWatch_windows_B2_20261119_163335_08af63b4.zip |
| ZIP SHA-256 | `f7362b8ccf1e8daf10c311666a973b156936273314276bb8146a4301815fb134` |
| report.json SHA-256 | `1b6f3c3605b3fd1aa64e5bc94cd8c69a44fc1ad234c7617291748e1631754d77` |
| 실행 JAR SHA-256 | `e637c843e33491aab5708817f03547cde7055c38b1e7c575550202734c632ee5` |
| B2 도구 SHA-256 | `f33c46f7f150d2f9af65e7271656307070fe9ba20df9e35015f5cbf10bdfb848` |
| Git main HEAD 전후 동일 | `5ed28882ddcb5a808ebe1c3da58a9036ed6222c2` |
| 소스 | Windows 실행 전후 원본47개 동일. JUnit과 HTTP의 앱 class32개 내용 동일. 현재 문서/도구 개정 뒤에도 runtime37개 해시 일치 |

원문은 `docs/verification/BankWatch_verification_manifest.json` schema1.4.0의 `windows_evidence.B1/B2`에서 파일명→내장SHA로 연결한다. `python scripts\verify-manifest.py --archive-only --extract output\evidence-v110`으로 SHA 이름의 원문을 추출할 수 있다.
문서·도구 편집은 실행 후의 정적 개정이다. 원본47개 전체 해시와 실행 runtime37개의 역할을 구분한다.
전체 HTTP의 추가 고객 입력 후 최종 DB는 원본21·경보3·근거18·사건3·이력12이며 사건3개 모두 version3/종료, 계정3개 접근가능, 임시 관찰함수0이다. SC01 단독7건의 기대값과 구분한다.
Windows 브라우저 버튼 조작·Excel 실제 표시·사람 발표/영상·원격 Actions·전체 Compose app/bootstrap 기동은 이번 B2 미실행이다. 보존한 과거 Linux 브라우저/600초 리허설과 v2 결과를 이번 Windows 실행으로 옮기지 않는다.
