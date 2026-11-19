# BankWatch v1.10 — 공개용 소스

이 폴더는 실제 Windows에서 검증한 BankWatch v1.10의 공개 사본입니다.
Spring Boot/Java/SQL/화면/샘플/Maven/통합 테스트의 runtime37개 파일은 검증본과 해시가 같습니다.

## 웹에서 체험하기

https://us0479.github.io/bankwatch/ 에서 가상 데이터로 조작할 수 있습니다.
GitHub Pages 체험판은 브라우저 시뮬레이션이며 Java 서버/DB 인증·잠금·교착을 실행하지 않습니다.
실제 앱의 검증 결과는 https://us0479.github.io/bankwatch/verification-summary.json 입니다.

## 공개판과 제출 원본

공식 문서 버전은 v1.10, 사용자 지정 제출일은 2026-11-29입니다.
공개판 표기는 v1.10-public.1입니다. Java/SQL 등 실행 코드는 바꾸지 않았습니다.
비밀번호·토큰·운영자 실제 계정 정보·비공개 검증 로그는 공개판에 없습니다.
공개 매니페스트/검증기/패키징 안내는 원문 로그 추출 대신 현재 파일52개와 runtime37개 지문을 확인합니다.
제출 문서 PDF/XLSX/Markdown은 원본을 보존했습니다. 그 안의 schema1.4 실행 원문/추출 지시는 비공개 제출본을 설명하며 공개판에서는 원문을 추출하지 않습니다.
원본 제출 ZIP과 공개용 ZIP은 내용과 SHA-256이 다릅니다. 공개판을 원본 ZIP과 바이트가 같다고 주장하지 않습니다.

## 빌드와 실제 앱

Java21, Maven Wrapper3.9.16, PostgreSQL16이 필요합니다. Windows cmd:

    python scripts\sync-assets.py
    mvnw.cmd -P!postgres-it validate
    mvnw.cmd -P!postgres-it clean package

SQL 원본은 db/입니다. 새 DB/소유자/실행역할을 준비하고 소유자로 schema.sql → seed.sql → grant_runtime.sql을 적용합니다.
seed는 가상 고객3명과 고정 R02만 생성합니다. 운영자는 별도 OperatorBootstrap이 로컬 비밀번호로 생성합니다.
자세한 초기화/환경변수 안내는 아래의 실행 안내를 따르세요. 호스트 psql이 없으면 Docker 내부 psql로 같은 SQL을 적용할 수 있습니다.

실제 테스트는 새 bankwatch_test_* DB와 owner/app/verifier 역할을 따로 준비해 실행합니다.
verifier에만 pg_signal_backend를 부여하고 앱 역할에는 부여하지 않습니다.

    mvnw.cmd -Ppostgres-it verify

표준 빌드의 DB시험0과 명시적 통합 테스트10을 구분합니다.
결과는 target/failsafe-reports/에 생성됩니다. 비밀번호를 Git에 저장하지 마세요.
PUBLIC SOURCE 검증/재패키징:

    python scripts\verify-manifest.py --json
    python scripts\package-source.py --output-dir output\public-source

이 검증은 공개 파일 지문 확인이며 비공개 실행 원문의 재검증이나 앱 재실행이 아닙니다.

## PostgreSQL과 Java를 직접 실행

이 절은 새 로컬 PostgreSQL 16 DB를 준비하는 예시입니다. `.env`는 Compose가 읽으며 Java/Maven 프로세스에는 자동 적용되지 않습니다. 이미 준비된 역할·DB가 있으면 그 소유권과 권한을 확인해 사용합니다.

역할과 DB가 아직 없는 경우 PostgreSQL 관리자 연결을 엽니다.

```cmd
set "PGCLIENTENCODING=UTF8"
psql -X -U postgres -d postgres
```

다음은 **psql 안에서** 입력합니다. `\password`는 로컬에서 비밀번호를 받는 psql 명령입니다.

```text
CREATE ROLE bankwatch_owner LOGIN;
\password bankwatch_owner
CREATE ROLE bankwatch_app LOGIN;
\password bankwatch_app
CREATE DATABASE bankwatch_db OWNER bankwatch_owner;
\q
```

저장소 루트의 cmd에서 스키마 소유자로 SQL을 순서대로 적용합니다. `schema.sql`은 신규·비어 있는 업무 스키마용이며 기존 업무 DB에 덮어쓰는 마이그레이션이 아닙니다.

```cmd
set "PGCLIENTENCODING=UTF8"
psql -X -v ON_ERROR_STOP=1 -h localhost -U bankwatch_owner -d bankwatch_db -f db\schema.sql
psql -X -v ON_ERROR_STOP=1 -h localhost -U bankwatch_owner -d bankwatch_db -f db\seed.sql
psql -X -v ON_ERROR_STOP=1 -h localhost -U bankwatch_owner -d bankwatch_db -f db\grant_runtime.sql
python scripts\sync-assets.py
mvnw.cmd clean package
```

초기화 전용 cmd에서 다음을 실행한 뒤 창을 닫습니다. `set /p`는 로컬 입력이며 입력을 마스킹하지 않습니다. JAR 파일명은 현재 `pom.xml`의 버전 2.0.0에 따릅니다.

```cmd
set "BANKWATCH_DB_URL=jdbc:postgresql://localhost:5432/bankwatch_db"
set "BANKWATCH_BOOTSTRAP_DB_USER=bankwatch_owner"
set /p "BANKWATCH_BOOTSTRAP_DB_PASSWORD=DB 소유자 비밀번호: "
set /p "BANKWATCH_ADMIN_PASSWORD=관리자 초기 비밀번호: "
set /p "BANKWATCH_ANALYST_A_PASSWORD=담당자 A 초기 비밀번호: "
set /p "BANKWATCH_ANALYST_B_PASSWORD=담당자 B 초기 비밀번호: "
java -Dloader.main=kr.ac.bankwatch.OperatorBootstrap -cp target\bankwatch-2.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

이후 새 cmd에서 저장소 루트로 이동해 앱 역할로 실행합니다. 웹 앱에는 소유자 초기화 변수가 필요하지 않습니다.

```cmd
set "BANKWATCH_DB_URL=jdbc:postgresql://localhost:5432/bankwatch_db"
set "BANKWATCH_DB_USER=bankwatch_app"
set /p "BANKWATCH_DB_PASSWORD=앱 DB 비밀번호: "
python scripts\sync-assets.py
mvnw.cmd spring-boot:run
```

빌드한 JAR의 직접 실행 명령은 `java -jar target\bankwatch-2.0.0.jar --server.address=127.0.0.1`입니다. 최신 HTTP 검증은 이 JAR 실행 방식과 별도 소유자 bootstrap을 Linux에서 확인했습니다. 위 Windows cmd와 Maven 개발 서버 실행의 직접 재현은 이번 검증에 포함되지 않습니다.

운영자 로그인 ID와 역할은 `admin`(ADMIN), `analyst_a`·`analyst_b`(ANALYST)입니다. 역할별 권한과 담당자·버전 조건은 버튼 표시와 별개로 서버가 검사합니다.

기본 주소 `/`에서 로그인하면 **경보·사건**과 **접속 기록** 두 메뉴를 사용합니다. ADMIN은 로그 입력·탐지·원인 사건의 잠금 해제를, ANALYST는 미배정 경보 선점과 본인 담당 사건의 보호·종료를 처리합니다. 구버전 충돌 시 최신 상태를 조회하고 작성한 사유를 보관하며, 사용자가 조치를 다시 확인해야 재전송합니다.

## 실제 PostgreSQL JUnit

[PostgresIntegrationTest](src/test/java/kr/ac/bankwatch/PostgresIntegrationTest.java)는 현재 서비스를 실제 PostgreSQL에 연결합니다. H2나 삭제된 검증 센터를 사용하지 않습니다.

| 명령 | DB 테스트 실행 여부 |
|---|---|
| `mvnw.cmd clean package` 또는 `postgres-it`가 비활성인 `mvnw.cmd verify` | 테스트 소스 컴파일. 이 통합 테스트는 Surefire 실행에서 제외. |
| `mvnw.cmd -Ppostgres-it verify` | 별도 DB 입력이 준비되면 Failsafe가 실제 통합 테스트를 실행. |

`BW_INTEGRATION_TEST=true` 환경 변수도 POM의 프로필을 자동 활성화합니다. 현재 CI는 명시한 `-Ppostgres-it` 경로를 사용합니다. 환경 변수만으로 프로필을 활성화하는 방식은 별도로 재현하지 않았습니다.

직접 실행 절의 `bankwatch_owner`·`bankwatch_app` 역할이 준비된 로컬 서버에서, PostgreSQL 관리자 psql 연결로 다음 **별도 테스트 DB**를 준비할 수 있습니다. 이미 있는 역할·DB는 재생성하지 않습니다.

```text
CREATE ROLE bankwatch_test_verifier LOGIN;
\password bankwatch_test_verifier
GRANT pg_signal_backend TO bankwatch_test_verifier;
CREATE DATABASE bankwatch_test_local OWNER bankwatch_owner;
\q
```

검증 역할의 연결 종료 권한은 SC-04용이며 `bankwatch_app`에 부여하지 않습니다. 테스트 DB는 `bankwatch_test_*` 이름의 PostgreSQL 16 DB이고, 실행 전 `bankwatch` 스키마가 없어야 합니다. 역할과 접속 비밀번호는 미리 준비하고 테스트가 스키마·권한·시드를 만듭니다.

저장소 루트의 검증 전용 cmd에서 환경 변수 **6개**를 설정합니다.

```cmd
set "BANKWATCH_TEST_DB_URL=jdbc:postgresql://localhost:5432/bankwatch_test_local"
set "BANKWATCH_TEST_OWNER_USER=bankwatch_owner"
set /p "BANKWATCH_TEST_OWNER_PASSWORD=검증 DB 소유자 비밀번호: "
set /p "BANKWATCH_TEST_APP_PASSWORD=검증 앱 역할 비밀번호: "
set "BANKWATCH_TEST_VERIFIER_USER=bankwatch_test_verifier"
set /p "BANKWATCH_TEST_VERIFIER_PASSWORD=별도 검증 역할 비밀번호: "
mvnw.cmd -Ppostgres-it verify
```

프로필은 테스트 프로세스에 `BW_INTEGRATION_TEST=true`를 전달합니다. 테스트는 자신이 생성한 업무 스키마에서만 픽스처를 초기화하고, 종료 시 연결 풀과 그 스키마를 정리합니다. DB 자체를 삭제하지 않습니다. 실행 뒤 생성되는 결과 경로는 `target/failsafe-reports/failsafe-summary.xml`과 `target/failsafe-reports/TEST-kr.ac.bankwatch.PostgresIntegrationTest.xml`입니다. TEST-10은 `target/failsafe-reports/bankwatch-test10-query-plans.json`, TEST-11은 `target/failsafe-reports/bankwatch-test11-deadlock.json`에 실제 원문·환경·관련 소스 SHA를 기록합니다.

현재 JUnit은 10개입니다. 최신 실행은 **2026-11-17 21:16 KST**의 새 `bankwatch_test_*` DB에서 수행했고 10개 모두 통과했습니다. 실패·오류·건너뜀·flakes는0, JUnit 시간은4.824초입니다. Docker VFS 복제의 공간 부족 후 같은 이미지의 PostgreSQL16.16을 로컬 프로세스로 실행했습니다. 아래 수치는 이 새 실행의 별첨 증거에 근거합니다.

| 시나리오·테스트 | 실제 확인하는 내용 |
|---|---|
| SC-01 / TEST-02·04·07 | 정상 7건·동일 재입력·R02·근거·재탐지·선점·잠금·관리자 해제·종료 |
| SC-03 / TEST-03 | 첫 신규 INSERT 실행을 확인한 뒤 없는 고객 조회가 실패하고 새 입력 전체 롤백 |
| TEST-05 | 41건의 경계 샘플에서 후보 0 |
| SC-02 / TEST-06 | 다른 ANALYST 두 명의 선점 후 승자의 두 DB 세션이 같은 버전으로 보호 잠금을 경쟁. 각각 성공 1·409 충돌 1. 이어진 별도 구버전 종료도 409·DB 상태 불변 |
| TEST-01·07 | 역할·담당자·사유·판정·원인 사건 검사, 종료와 계정 접근 상태 분리 |
| TEST-08 | 이력 저장의 앱 예외·SQL 예외 후 조치 전체 롤백 |
| SC-04 / TEST-08 | 원인·버전 갱신 후 별도 검증 연결이 실제 백엔드 종료, 이전 상태 복구와 새 요청 성공 |
| TEST-09 | KST 월별 라우팅·복합 PK/FK·경보 UNIQUE·CHECK·NULL·최소 권한·범위 밖 입력 롤백 |
| TEST-10 | queryPlansComparePruningAndIndexOnIdenticalData: 합성30,000행·실제 QRY-05/바인딩·pruning on/off·후보 인덱스 전후 같은 결과와 원문 계획 |
| TEST-11 | deadlockRetriesWholeProtectionWithoutMissingOrDuplicateChanges: 두 실제 연결의40P01·전체 롤백·새 트랜잭션 재시도·최종 원인/버전/이력·일반 오류 비재시도 |

직접 FK 위반은 TEST-09이며 SC-03의 업무상 고객 조회 실패를 대신하지 않습니다. 이 JUnit은 서비스·DB 검증이며 브라우저·인증 필터의 전체 흐름을 실행하는 테스트는 아닙니다.

TEST-10 입력은 월별10,000행·총30,000행, 고객 C1001·LOGIN_FAILED·KST11월 `[2026-11-01,2026-12-01)`·커서 없음·limit20입니다. 실제 조회 서비스의 SQL/바인딩을 포착하고 집계100·화면 목록20·실제fetch21행과 원본 fingerprint 동일을 확인했습니다. 아래는 **11-17 21:16 KST**의 단발 warm JSON 계획 값입니다. `shared_buffers=32MB`, `work_mem=4MB`, 시간대는Asia/Seoul입니다.

| 조건 | 읽은 월 파티션 수 | 실제 반환 행 | 실행 시간(ms) | shared hit/read | 선택 인덱스 |
|---|---|---|---|---|---|
| pruning on·후보 전 | 1 | 21 | 0.667 | 44/0 | 11월 PK |
| pruning off·후보 전 | 3 | 21 | 0.892 | 46/0 | 10·11·12월 PK |
| pruning on·후보 후 | 1 | 21 | 0.677 | 44/0 | 11월 PK |

TEXT/JSON `EXPLAIN (ANALYZE, BUFFERS, SETTINGS)`는 별도 실행이며 위 요약은 JSON에서 읽었습니다. 후보를 강제로 사용하지 않았고 추가 후에도 자연 계획은 PK backward scan을 선택했습니다. 후보 인덱스는 시험 뒤 제거했고 기본 DDL에는 없습니다. 작은 단발 시간 차이를 일반적인 성능 향상으로 해석하지 않습니다. QRY-06 집계100 확인과 그 집계 SQL 자체의 인덱스 전후 계획 미실행도 구분합니다.
TEST-11은 서로 다른 고객·담당자의 실제 보호 업무에 시험 전용 트리거를 걸어 교착을 만들었습니다. 운영 코드의 고객→사건 순서는 유지했습니다. 최신 실행의 PID39894/tx749와 PID39884/tx748에서 실제40P01을 확인했고, 실패tx749의 고객·사건·이력 전체가 초기 스냅샷으로 복원된 뒤 같은PID39894의 새tx750이 성공했습니다. 업무3시도·2커밋, 각사건version1·자기 원인·CLAIM/LOCK_ACCOUNT 각1로 누락·중복이 없었습니다. 일반400/403/409는 각1회·DB불변이었으며 이 값은 서비스가 반환한 오류 상태입니다.
Db.tx는40P01/40001에만 전체 업무를 새 트랜잭션으로 최대2회 재시도합니다. 이번 실제40P01 재시도는1회였고 한도 소진·반복 교착·이 시험의 강제40001은 미실행입니다. 과거 E-DETECT의40001과 SC-04의 연결 종료 비재시도는 별도 시험입니다. 시험 픽스처·클래스 소유 스키마를 정리하고 임시 로컬 PostgreSQL 클러스터도 종료·제거했습니다.

## API와 SQL 위치

현재 공개된 업무 경로는 아래와 같습니다. 상태 변경은 세션과 CSRF 토큰이 필요하며 운영자 ID를 요청 본문에서 받지 않습니다. bigint ID는 응답에서 문자열로 전달합니다.

| 요청 | 입력·권한 |
|---|---|
| `GET /api/session` | 로그인 전후의 세션·CSRF·현재 운영자 공개 정보. `lab_enabled=false`는 임시 호환 필드. |
| `POST /login`, `POST /logout` | 운영자 세션 인증·로그아웃. 로그인은 폼의 `username`, `password`. |
| `GET /logs` | 두 역할의 고객·기간·유형·복합 커서 필터 및 집계 |
| `POST /logs/import` | ADMIN, multipart `file` 하나 |
| `GET /alerts`, `GET /alerts/{alertId}` | 두 역할의 경보·전체 원본 키 근거·사건·이력 조회 |
| `POST /alerts/detect` | ADMIN, JSON의 `from`, `to` 두 시각 문자열 |
| `POST /alerts/{alertId}/claim` | ANALYST, 빈 본문 또는 `{}` |
| `POST /incidents/{incidentId}/lock` | 담당 ANALYST, JSON `version`, `reason` |
| `POST /incidents/{incidentId}/unlock` | ADMIN, JSON `version`, `reason`; 현재 잠금 원인 사건 |
| `POST /incidents/{incidentId}/close` | 담당 ANALYST, JSON `version`, `reason`, `resolution` |

`version`은 0 이상의 JSON 정수, `reason`은 양끝 공백 정리 후 1~2,000자이며 공백만으로 구성할 수 없습니다. 판정은 `CONFIRMED`, `FALSE_POSITIVE`, `INCONCLUSIVE`입니다. 중복 JSON 키·추가 본문 항목·문자열/소수 버전·뒤따르는 JSON은 거절합니다. 구형 업무 `/api/...` 경로와 `/api/login`·`/api/logout` 인증 호환 체인은 제거했습니다. 구형 인증 POST는 올바른 CSRF 토큰이 있어도 비로그인 401·로그인 403으로 거절되며, 로그인·로그아웃·계정 전환을 수행하지 않습니다.

정상 재현 입력은 [events-r02.csv](samples/events-r02.csv)이며 탐지 기간은 **2026-11-02 10:00 이상, 10:10 미만 KST**입니다. 기대·확인 값은 후보 성공 **2**, 대표 성공 ID **6**, 경보 **1**, 근거 **6**, 재탐지 신규 경보 **0**입니다. 정상 대응 종료 시 사건 버전은 3, 이력은 CLAIM·LOCK_ACCOUNT·UNLOCK_ACCOUNT·CLOSE 각 1건, 사건 CLOSED·계정 ACTIVE입니다.

DDL·기초 데이터·권한의 원본은 [schema.sql](db/schema.sql), [seed.sql](db/seed.sql), [grant_runtime.sql](db/grant_runtime.sql)입니다. 실제 업무 SQL은 다음 서비스의 QRY 주석과 상수에 있습니다.

| QRY | 현재 소스 |
|---|---|
| 01 | [SecurityConfig](src/main/java/kr/ac/bankwatch/SecurityConfig.java), [ApiController](src/main/java/kr/ac/bankwatch/ApiController.java) 및 각 서비스의 현재 운영자 검사 |
| 02~04 | [EventService](src/main/java/kr/ac/bankwatch/event/EventService.java): 고객 조회·복합키 INSERT·기존 내용 확인 |
| 05~06 | [EventQueryService](src/main/java/kr/ac/bankwatch/event/EventQueryService.java): 기간 필터·복합 커서·구간/결과 집계 |
| 07~10, 23 | [DetectionService](src/main/java/kr/ac/bankwatch/detection/DetectionService.java): 고정 규칙·CTE/윈도우 후보·경보·근거 저장 및 검증 |
| 11~12, 22 | [AlertQueryService](src/main/java/kr/ac/bankwatch/detection/AlertQueryService.java): 경보 JOIN·원본 복합키 JOIN·운영자 이력 JOIN |
| 13~16 | [IncidentClaimService](src/main/java/kr/ac/bankwatch/incident/IncidentClaimService.java): 경보 잠금 함수·잠금 후 별도 조회·사건/CLAIM 삽입 |
| 14·16 변형, 17~21 | [IncidentActionService](src/main/java/kr/ac/bankwatch/incident/IncidentActionService.java): 고객→사건 잠금·원인·버전·종료·이력 |

명세 내부 r9는 실제 바인딩 SQL 23개와 선언된 함수 10개의 원형·파일·역참조를 연결했습니다. 대표 SQL은 13개이며 업무 JOIN 3개(QRY-11·12·22), 집계 3개(QRY-06·08·23), CTE 2개(QRY-08·23), 윈도우 1개(QRY-08)를 확인했습니다. QRY-23은 저장된 근거를 경보·원본 전체 복합키에 다시 JOIN하여 검사하고 불일치 시 탐지 전체를 롤백합니다. 교차 행 조건을 일반 CHECK만으로 보장했다고 기록하지 않습니다.

FUNC-05는 실제 `AlertQueryService.alerts` 목록 함수이고, FUNC-10은 `detail` 상세·근거·이력 함수로 FEAT-04·FEAT-06이 공유합니다. FUNC-10은 QRY-01·11·12·22를 사용하며 QRY-14는 선점·조치의 대상 조회입니다. 02 계획의 책임 예시와 현재 구현의 연결 차이를 명세·시나리오에 반영했습니다.
