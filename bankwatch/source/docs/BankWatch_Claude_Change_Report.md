# BankWatch Claude 변경 보고서 — v1.11 / v1.11-public.1 (CP3 최종 후보)

| 항목 | 값 |
|---|---|
| 작성 | 2026-11-19 KST, Claude 검토 세션 |
| 입력 | 공개판 `BankWatch-v1.10-public-source.zip` SHA-256 `0e0b5e02fdd95c02ce5295de0a1d32d32539d767aa3edad1aae61806113ce015` (private 원본·Windows 원문 증거는 제공되지 않음) |
| 결과 | 공식 문서 v1.11, 공개판 v1.11-public.1 **수정 후보**. private 저장소 병합·Windows PG 재시험·commit/push·Pages 배포는 미실행 |
| 코드·SQL 변경 | **0건**. Java·SQL·화면·샘플·Maven의 runtime37개는 v1.10과 바이트 동일 |

## 0. 기존 성과와 이번 수정의 구분

8테이블 설계, DDL, Java 앱, 통합 테스트, ERD·데이터 사전·명세, Windows B1/B2 검증, 공개 웹은 **기존 사용자·Codex 작업(v1.10)**이다. 이 보고서의 변경은 그 위에 Claude가 한 검토·문서 보강·도구 추가뿐이다.

## 1. 발견 사항(FIND)

| ID | 판정 | 대상 | 내용 | 조치 |
|---|---|---|---|---|
| FIND-01 | **철회(오탐)** | `docs/BankWatch_spec.xlsx` Query!D | QRY-08/11/14/23의 D셀이 문장 중간에서 끝나 절단으로 보였다. 렌더링으로 확인하니 A열이 빈 "이어짐 2/2·2/3·3/3" 행에 나머지가 있고, 합치면 셀 메모의 전체 SQL과 같다(QRY-11은 목록 SQL 전체가 3행, FUNC-10 상세 변형은 메모에만 있으며 시나리오에 명시됨). 의도된 구조다 | 한때 셀을 복원했다가 **전부 되돌렸다**. XLSX는 원본 바이트 그대로(v1.10 표기 유지) |
| FIND-02 | 필수(교수 피드백 3·4) | `docs/BankWatch_user-scenario.md` | 제출 시나리오에 데이터 생성 위치·수집 방식·탐지 주체/시점(서버 측 사후 탐지)·DPI 제외와, 도메인 선택·DB 필요성·다른(경계) 시나리오 설명이 명시되지 않았다(등록문 초안에만 있었다) | §1.1·§1.2·§1.3 추가 |
| FIND-03 | 필수(교수 양식) | 같은 파일 | 4절이 양식의 4.1 구성 요소·4.3 트랜잭션 표·4.4 인덱스 표·4.5 실행 환경 구조가 아니었고, 실제 QRY 표가 QRY-19에서 끝났다(QRY-20~23 누락). 시나리오별 실행 예시가 없었다 | 4.1·4.3 표·4.4·4.5 추가, 기존 4.1/4.2 제목만 4.2/4.3 하위로 재배치, QRY-20~23 행을 명세 B;G;H 열에서 기존 19행과 같은 규칙으로 생성, SC-01~04 실행 예시·결과 추가 |
| FIND-04 | 정보(무수정) | README·시나리오 | `bankwatch_app`에는 `pg_signal_backend`가 없음을 실측(다른 역할 backend 종료 시 42501). 다만 PostgreSQL은 같은 역할의 backend 종료를 항상 허용한다. 기존 문서는 "검증 역할에만 부여"라고만 써서 사실과 어긋나지 않는다 | 등록문·시나리오 6.1에 정확한 표현으로 기록. 코드·권한 변경 없음 |
| FIND-05 | 도구 | `scripts/verify-manifest.py`, `scripts/package-source.py` | 검증기가 인벤토리 52개를 상수로 요구해 새 등록·감사·인계 파일을 패키지에 넣을 수 없었다. 패키저 선택 목록에도 없었다 | 스키마 `public-1.1.0`은 매니페스트의 `summary.inventory_files`로 검사(1.0.0은 52 유지), 패키저 REQUIRED에 15개 추가 |

정적 검토에서 다음은 문제없음을 확인했다: R02 질의 의미(무작위 대조 포함), 10분 `date_bin` 기준점 KST, `EXCLUDE GROUP`, 대표 선택, 경보 UQ·부분 UQ·`incident.alert_id` UQ, 월별 RANGE와 PK, 근거 전체 복합 FK, 계산 상태, 잠금 순서(고객→사건), 버전 조건, 종료 후 ADMIN 해제, SC03 순서, 권한 최소화, 함수 200줄, REQ↔SC·REQ→FEAT→FUNC→QRY·TEST→REQ 양방향, 명세 FUNC 원형의 소스 원문 일치, 데이터 사전↔DDL 컬럼·제약명·DDL SHA, ERD 관계 수·키, 공개 웹 소스=공개판, 다운로드 SHA256SUMS.

## 2. 변경 파일

| 경로 | 작업 | 이유 | 관련 | 이전 SHA-256 | 이후 SHA-256 | diff |
|---|---|---|---|---|---|---|
| `docs/BankWatch_user-scenario.md` | 수정 | FIND-02·03 | REQ-01~16, SC-01~04, TEST-01~11 | `b8bb0063d69018125486dd2757db708b5b735bd77f6e5088e81ea108ac2afcac` | `ff460236226db9af14f1ea58bbedf1ce4f5ae4ea0e74aa39014a56614bbd6839` | +212 / −5 |
| `README.md` | 수정 | 버전·파일 수 표기, v1.11 검토 안내, CP3 로그 범위 문구 | — | `5f12891d308fda9fbef6747864f8269382d8b89c942debb58de91ceb3cfc135a` | `7e2e528bd797d16758a5fc39ad4aff7294447fea47f8a63a40f7075479ec6a4a` | +22 / −7 |
| `scripts/verify-manifest.py` | 수정 | FIND-05, CP3 원문 로그 거부 | — | `e86d21bc82340f154906ae0d1380965a750fd7e6a8284c17d447224af5679853` | `c77b2789a0ed0ee196bf20bbd4b8b7ae92c7811db9a511b64c113aeee793a72a` | +9 / −4 |
| `scripts/package-source.py` | 수정 | FIND-05, CP3 선택 목록 | — | `ffe2d56fa9033861377f333797838da95e090f62a3d0930cee23b616d10f7bb7` | `34f52ff56b6b7b622686640f27086d6ad19becfd2bc913e4e8a16d67c1ab1751` | +9 / −1 |
| `docs/verification/BankWatch_verification_manifest.json` | 재생성 | v1.11-public.1 인벤토리. runtime37 표는 그대로, v1.10 해시는 `revisions`에 보존 | — | `d40774c6ee28c01d1cdc73b33038fdb95e5e861ab33b0dbf627a9ac103f9283a` | 외부 `SHA256SUMS.txt` 참조(재귀 해시 회피) | 전체 재생성 |
| `docs/BankWatch_topic_registration.md` / `.txt` | 신규 | 주제 등록문(이미지 5항목) | — | — | 매니페스트 인벤토리 | — |
| `docs/BankWatch_Claude_Change_Report.md`, `docs/BankWatch_Claude_Session_Handoff.md`, `docs/REVIEW_STATE.json`, `docs/RESUME_PROMPT.md` | 신규 | 변경 기록·채팅 없는 재개 | — | — | 매니페스트 인벤토리 | — |
| `scripts/audit/funclen.py`, `sqlprep.py`, `pg_scenarios.sh`, `pg_concurrency.sh`, `r02_fuzz.py` | 신규 | 재현 가능한 감사 도구 | TEST-02~09 대응 SQL 수준 | — | 매니페스트 인벤토리 | — |
| `docs/verification/claude/BankWatch_claude_check_summary.json` | 신규(CP3) | 공개용 검증 요약(수치만). CP2의 원문 로그 3개·funclen.json은 공개판에서 제외하고 감사 묶음 `audit-raw/claude-cp2-sql-run/`에 보존 | — | — | `1742e8bc2c2cb573998bbd429569c13f5fb02e5f5e583683745342eca49ac43b` | — |

변경하지 않은 제출물: `docs/BankWatch_erd.pdf`(v1.10 표기 유지, 내용 무변경), `docs/BankWatch_spec.xlsx`(v1.10), `docs/BankWatch_data_dictionary.md`(v1.10). 그래서 v1.11 제출 세트는 "시나리오 v1.11 + ERD·명세 v1.10(무변경)"이다.

### 2.1 시나리오 diff 요약
- 머리표: 공식 문서 버전 `v1.10` → `v1.11 (…runtime37은 v1.10과 동일)`, 검토 근거에 v1.11 개정 범위 한 줄 추가.
- 추가: §1.1 데이터 생성 위치·수집 방식·탐지 주체와 시점(표), §1.2 도메인 선택·DB 필요성(10행 표)·정상 기기 변경과 경계 상황(`events-boundaries.csv` 7종+대조), §1.3 기능 축소.
- 추가: SC-01~04 각 "실행 예시"(API 경로·JSON 키·기대 값)와 "결과". 기존 표·"실제 결과" 줄은 그대로.
- 4절: 4.1 구성 요소(텍스트 다이어그램·표) 신규, `4.1 모델·제약·정규화` → `4.2 테이블 구성·제약·정규화`, `4.2 실제 QRY·트랜잭션·인덱스` → `4.3 트랜잭션·동시성 처리`(작업별 표 신규) + `#### 실제 QRY 목록`(QRY-20~23 4행 추가), 4.4 인덱스 표 신규, 4.5 실행 환경 신규 + `### Windows 초기화·실행` → `#### Windows 초기화·실행`.
- 추가: §6.1 v1.11 문서 개정 시 추가 확인.
- 전체 patch는 외부 파일 `BankWatch-v1.11-public.patch`(v1.10-public.1 → CP3 후보)에 있다.

### 2.2 도구 diff (verify-manifest.py)
```diff
-    require(manifest['kind']=='bankwatch.public_source_snapshot' and manifest['schema_version']=='public-1.0.0','Use the public-edition manifest')
+    require(manifest['kind']=='bankwatch.public_source_snapshot' and manifest['schema_version'] in ('public-1.0.0','public-1.1.0'),'Use the public-edition manifest')
+    expected=52 if manifest['schema_version']=='public-1.0.0' else manifest['summary']['inventory_files']
-    require(MANIFEST_PATH not in files and len(files)==52,'Inventory/self-exclusion mismatch')
+    require(MANIFEST_PATH not in files and isinstance(expected,int) and len(files)==expected,'Inventory/self-exclusion mismatch')
```
package-source.py: `REQUIRED`에 신규 12개 경로(CP2의 15개에서 원문 로그 4개를 빼고 요약 1개 추가), `EXECUTABLES`에 두 감사 셸 스크립트 추가.
verify-manifest.py(CP3): 스키마 1.1.0에서 `.log` 경로가 있거나 `disclosure.raw_execution_logs_included`가 false가 아니면 실패.

## 3. 시험

| 시험 | 대상 소스 | 환경 | 결과 |
|---|---|---|---|
| 함수 200줄 | 수정 후 작업 사본 전체(직접 작성 Java/JS/Python, Wrapper 제외) | Python 3.13 | PASS 최대 60줄 |
| DDL 적용·재적용 거절·seed 멱등 | `db/*.sql`(v1.10 동일) | 새 로컬 PostgreSQL 16.15, DB `bankwatch_test_claude`, 소유자/앱/검증 역할 분리 | PASS |
| 서비스 SQL 시나리오 | Java 상수 SQL 26개(PREPARE, `bankwatch_app`) | 같은 클러스터 | PASS 37/37 |
| 동시 세션 SC-02·SC-04 | 같은 SQL | 실제 병렬 psql 세션, 검증 역할 `pg_signal_backend` | PASS 15/15 |
| R02 무작위 대조 | QRY-08 | 2,000행×5, 150행×8 | PASS 13/13 |
| 명세 교차참조 | `BankWatch_spec.xlsx` | openpyxl + LibreOffice 재계산 | PASS, 집계 10/4/10/23/11 |
| 공개판 인벤토리 | v1.11-public.1 | `python3 -I scripts/verify-manifest.py --json` | 결과는 인계 문서·외부 체크섬 참조 |
| Maven validate/package, `-Ppostgres-it verify`, HTTP·브라우저 | — | — | **미실행**: Maven Central 403, 로컬 저장소 없음. 본인 Windows에서 실행 필요 |

Windows B1(JUnit 0)·B2(JUnit10/HTTP48/132)는 v1.10 runtime의 기존 증거이며, runtime37이 동일하므로 그대로 유효하다. 이를 Claude가 다시 실행했다고 쓰지 않는다.

## 4. Git·게시

commit 0, push 0, Pages 배포 0. 이 공유 계정의 GitHub 토큰은 무효이며 비밀번호/PAT를 요청하지 않았다. 적용 절차는 `BankWatch_Claude_Session_Handoff.md` §6.

## 5. CP3 보완 — 포장·메타데이터만 (2026-11-19 KST)

Codex의 CP2 대조 결과(runtime37·ERD·명세·사전 바이트 동일 확인)에 따른 보완이다. **Java·SQL·화면 코드와 제출 문서 내용은 바꾸지 않았다.** 기존 시험을 새로 실행했다고 기록하지 않는다.

| 항목 | CP2 문제 | CP3 조치 |
|---|---|---|
| 홈페이지 `verification-summary.json`의 `public_source_package` | 판 이름만 v1.11이고 bytes 453166·entries 54·SHA `0e0b5e02…`(v1.10 값), `local_maven_validate` PASS 표기 | 최종 ZIP에서 bytes·entries·SHA를 **자동 계산**해 기록. Maven은 `NOT_RUN for v1.11 package`로 표기하고 v1.10 runtime의 Windows B1 기록임을 분리 |
| 공개 원문 로그 | 공개 ZIP에 `docs/verification/claude/*.log` 3개와 `funclen.json`이 있었으나 `raw_execution_evidence_included`는 false | 4개를 공개 트리에서 제거해 감사 묶음에만 보존. 공개에는 수치 요약 JSON과 `scripts/audit/` 도구 유지. packager 목록·manifest 재고(64)·disclosure·PACKAGE-INFO·검증기를 실제 포함 범위와 일치시킴 |
| 구버전 정리 | 갱신 ZIP은 덮어쓰기만 가능 | `SITE_DELETE_LIST`에 v1.10 공개 ZIP(예상 SHA `0e0b5e02…`)과 CP2 후보를 이미 적용했을 때만 남는 로그 4개를 기록하고, SHA가 일치할 때만 지우는 스크립트 제공. 체크섬의 v1.10 줄은 새 `SHA256SUMS.txt`로 대체 |
| 최종 구성품 색인 | 없음 | `FINAL_DELIVERY_INDEX.json`·`.md`: 파일별 역할·버전·SHA·공개 구분·검증 상태와 혼합 버전 구성(시나리오 v1.11 + ERD·명세·사전 v1.10)의 의도를 기록 |

CP3에서 실제로 실행한 것: 함수 길이 정적 검사(642블록·최대 60줄·초과 0), 매니페스트 재생성·`verify-manifest.py`(v1.11 64개·v1.10 52개 호환)·부정 시험, `package-source.py` 패키징과 압축 해제 재검증, 홈페이지·패키지 보고서·체크섬·manifest·상태 파일 값 상호 대조(`FINAL_DELIVERY_INDEX`의 cross_check). PostgreSQL SQL 수준 검사는 CP2 실행 결과이며 CP3에서 다시 실행하지 않았다(runtime37·SQL 무변경).

미실행 유지: Maven validate/package, `-Ppostgres-it verify`(JUnit), HTTP·브라우저, private 병합, commit/push, Pages 배포, LMS 제출. private 매니페스트 생성 도구가 있다고 가정하지 않으며 private 병합·최종 검증은 이후 Codex가 처리한다.

