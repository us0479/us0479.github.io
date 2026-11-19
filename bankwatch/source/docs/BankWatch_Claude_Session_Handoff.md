# BankWatch Claude 세션 인계 — 최종(CP3)

| 항목 | 값 |
|---|---|
| 기록일 | 2026-11-19 KST |
| 단계 | CP3_PACKAGING_METADATA_FIXED — CP2(검토·문서 개정·등록문) 위에 포장·메타데이터만 보완: 홈페이지 패키지 값 자동 반영, 공개판 원문 로그 제외, 구버전 삭제 목록, 최종 구성품 색인. 코드·SQL·화면·제출 문서 내용 무변경. private 병합·push·배포·Windows 재시험은 이후 Codex/사용자 몫 |
| 버전 | 공식 v1.11(시나리오 개정) / 공개 v1.11-public.1. ERD·명세·데이터 사전은 v1.10 무변경 |
| 입력 패키지 | 공개판 v1.10-public.1 ZIP `0e0b5e02fdd95c02ce5295de0a1d32d32539d767aa3edad1aae61806113ce015` / 웹 스냅샷 `4afe32291e7f22c5f2db23d0fd29482d5eda4a4e02c606622e46c6a9f80417a5` / 교수 XLSX 양식 `7488474c6db278eba530c502d0be47397be5bf534093e841f14efb9a869f5581` |
| 산출 패키지 해시 | 이 파일은 ZIP 안에 있으므로 ZIP 해시·크기·항목 수는 **외부 `SHA256SUMS.txt`와 `FINAL_DELIVERY_INDEX.json`**에만 기록한다. CP1·CP2 후보는 최종 묶음에서 제외(감사용 보관은 사용자 PC) |
| 작업 환경 | Anthropic 클라우드 Linux. Java 21.0.13, Maven 3.9.11(시스템), PostgreSQL 16.15 서버 바이너리, Python 3.13, LibreOffice. Maven Central 403, Docker 데몬 없음, GitHub 토큰 무효 |

## 1. 읽은 자료 / 읽지 못한 자료
- 읽음: 프로젝트 지침, 03·05·06·08·09·10, 01·02(검색 범위), PPT 텍스트, 교수 시나리오 양식, 공지·기존 등록 이미지, 공개 소스 54파일, 공개 웹 스냅샷 전체, 교수 XLSX 원본.
- 없음: private 원본 ZIP, Windows B1/B2 원문 증거, runtime.json, private main clone, LMS 실제 제출 상태. 읽었다고 쓰지 않았다.

## 2. 확정 계약(변경 금지)
R02 하나·KST 고정10분·신규기기 성공보다 엄격히 앞선 실패5건 이상·동일 시각 제외·시각/ID 첫 적격 성공 대표 / 8테이블·44컬럼·FK10 / 원본 PK(occurred_at,event_id)·KST 월별 RANGE / 근거 전체 복합 FK / 경보 UQ(rule_id,account_id,bucket_start) / 대표 최대1 부분 UQ / incident.alert_id UQ / 저장 status 없음 / SC01~04 정의 / 함수 200줄 / IP·5분·R01·편의기능 복원 금지 / REQ10·FEAT4·FUNC10·QRY23·TEST11 번호 유지.

## 3. 완료 작업
- 정적 검토(코드·DDL·문서·XLSX·ERD·웹) → FIND-01(철회), FIND-02·03(시나리오 개정), FIND-04(정보), FIND-05(도구). 상세는 `BankWatch_Claude_Change_Report.md`.
- 코드·SQL 변경 0. 수정 4개(시나리오·README·verify-manifest·package-source), 매니페스트 재생성, 공개판 신규 12개(인벤토리 64).
- CP3: 원문 로그 3개·funclen.json을 공개판에서 빼 감사 묶음 `audit-raw/claude-cp2-sql-run/`(체크포인트 ZIP 안)으로 옮기고 요약 JSON으로 대체.
- 주제 등록문 `docs/BankWatch_topic_registration.md`·`.txt`(LMS 붙여넣기용 CRLF).

## 4. 시험(이번 세션 실제 실행)
| 시험 | 명령 | 입력 | 결과 |
|---|---|---|---|
| 함수 200줄 | `python3 -I scripts/audit/funclen.py .` | 작업 사본 | 최대 60줄, 초과 0 |
| SQL 시나리오(CP2 실행, CP3 미재실행) | `PGDATABASE=bankwatch_test_claude bash scripts/audit/pg_scenarios.sh . prepared.sql` | samples 3종 | 37/37 |
| 동시성 | `bash scripts/audit/pg_concurrency.sh . prepared.sql <dir>` | 정상 7건 | 15/15 |
| R02 대조 | `python3 -I scripts/audit/r02_fuzz.py prepared.sql <seed> [rows]` | 무작위 | 13/13 |
| 명세 | 교차참조·LibreOffice 재계산 | 원본 XLSX | 10/4/10/23/11, 양방향 일치 |
| 공개 인벤토리·패키지(CP3 실행) | `verify-manifest.py --json`, `package-source.py`, 압축 해제 재검증, 값 상호 대조 | v1.11-public.1 CP3 | `FINAL_DELIVERY_INDEX.json`의 package·cross_check |
| Maven·JUnit·HTTP·브라우저 | — | — | 미실행(의존성 다운로드 불가) |

## 5. Git·원격 상태
| 저장소 | 이 세션 | 상태 |
|---|---|---|
| private `us0479/bankwatch-db` | 접근 없음 | 마지막 기록 main HEAD `5ed28882ddcb5a808ebe1c3da58a9036ed6222c2`(완성본 47개 미커밋). commit/push 미실행 |
| public `us0479/us0479.github.io` | 접근 없음 | 마지막 기록 배포 `1fe190b1ad138a228f3f2e4cf6720ede5b7dcc76`. 수정 후보는 `us0479.github.io-v1.11-site-update.zip` + `SITE_DELETE_LIST.json`. push·Pages 미실행 |

## 6. 본인 Windows 환경에서 적용·재시험·게시 (cmd)

### 6.1 private 앱 저장소 — 공개판 도구 4개는 덮어쓰지 않는다
private의 README·manifest(schema1.4)·verify-manifest·package-source는 공개판과 원래 다르다. 아래만 옮긴다.
```cmd
cd /d C:\Users\us021\source\bankwatch-learning\bankwatch-db
git status --short & git rev-parse HEAD
certutil -hashfile docs\BankWatch_user-scenario.md SHA256
rem 결과가 b8bb0063d69018125486dd2757db708b5b735bd77f6e5088e81ea108ac2afcac 일 때만 다음 복사(아니면 중단하고 차이 확인)
set "SRC=%USERPROFILE%\Downloads\BankWatch-v1.11-public\BankWatch"
copy /y "%SRC%\docs\BankWatch_user-scenario.md" docs\
for %f in (BankWatch_topic_registration.md BankWatch_topic_registration.txt BankWatch_Claude_Change_Report.md BankWatch_Claude_Session_Handoff.md REVIEW_STATE.json RESUME_PROMPT.md) do copy /y "%SRC%\docs\%f" docs\
xcopy /e /i /y "%SRC%\scripts\audit" scripts\audit
xcopy /e /i /y "%SRC%\docs\verification\claude" docs\verification\claude
```
원문 로그가 필요하면 체크포인트 ZIP의 `audit-raw/claude-cp2-sql-run/`에서 private 쪽 별도 감사 폴더로 옮긴다. private `package-source.py`·private 매니페스트 갱신 방법은 이 세션이 private 도구를 보지 못해 알 수 없다. **private manifest 생성 도구가 있다고 가정하지 않는다.** 병합·최종 검증은 이후 Codex가 처리한다.

재시험(새 전용 DB, 기존 DB 삭제 금지):
```cmd
python scripts\sync-assets.py
mvnw.cmd -P!postgres-it validate
mvnw.cmd -P!postgres-it clean package
mvnw.cmd -Ppostgres-it verify
```
runtime37이 같으므로 결과는 B2와 같아야 한다. 다르면 커밋하지 말고 기록한다. 이후 사용자 판단으로:
```cmd
git add docs\BankWatch_user-scenario.md docs\BankWatch_topic_registration.* docs\BankWatch_Claude_* docs\REVIEW_STATE.json docs\RESUME_PROMPT.md scripts\audit docs\verification\claude scripts\package-source.py
git commit -m "docs: v1.11 scenario revision, topic registration, Claude review records"
git push origin main
```
(force push 금지. 완성본 47개가 아직 미커밋이면 그 커밋을 먼저 할지 본인이 결정)

### 6.2 public 홈페이지 저장소
```cmd
cd /d C:\Users\us021\source\bankwatch-learning\us0479.github.io
git pull --ff-only & git status --short
rem 1) us0479.github.io-v1.11-site-update.zip 압축을 이 루트에 풀어 덮어쓴다(ZIP에는 저장소 파일만 있다. 파일 목록은 별도 us0479.github.io-v1.11-site-update.files.sha256.txt)
rem 2) 삭제 목록 적용: SHA가 SITE_DELETE_LIST.json 기록과 같을 때만 지우고 그 밖의 파일은 건드리지 않는다
powershell -ExecutionPolicy Bypass -File "%USERPROFILE%\Downloads\site-delete.ps1" -Root . -List "%USERPROFILE%\Downloads\SITE_DELETE_LIST.json"
git diff --stat
git add -A index.html README.md bankwatch
git commit -m "BankWatch v1.11-public.1: scenario revision, public source ZIP"
git push origin main
```
`git add`는 삭제도 반영되도록 `git add -A index.html README.md bankwatch`로 한다. 배포 뒤 확인: https://us0479.github.io/ → BankWatch 카드 → 체험, `bankwatch/downloads/BankWatch-v1.11-public-source.zip` 다운로드, `SHA256SUMS.txt`·`verification-summary.json`의 `public_source_package` 값과 대조. Pages는 정적 체험이며 Java/PostgreSQL 서버가 아니다.

## 7. 다음 작업 하나
**본인 PC에서 2026-11-20 23:59 KST 전에 `docs/BankWatch_topic_registration.txt` 본문을 LMS "[텀프로젝트] 주제 등록"에 온라인 텍스트로 붙여넣어 제출.** 그 뒤 6.1 재시험.

민감값 없음. 환경변수 이름만: PGHOST, PGPORT, PGDATABASE, BW_OWNER_ROLE, BW_VERIFIER_ROLE, BANKWATCH_* (README 참조).
