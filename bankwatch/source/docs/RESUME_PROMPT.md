# RESUME_PROMPT — BankWatch Claude 검토 재개용

현재 기록 단계: CP3_PACKAGING_METADATA_FIXED (공식 v1.11 / 공개 v1.11-public.1 CP3 후보, private 병합·push·배포·Windows 재시험 미실행). 최종 구성은 `FINAL_DELIVERY_INDEX.json`을 먼저 확인해. CP1·CP2 후보 ZIP은 최종본이 아니다.

이전 채팅은 삭제됐으므로 첨부한 `docs/BankWatch_Claude_Session_Handoff.md`, `docs/REVIEW_STATE.json`, `docs/BankWatch_Claude_Change_Report.md`, 최신 체크포인트 ZIP과 `SHA256SUMS.txt`를 기준으로 이어가줘.

1. 먼저 ZIP과 파일 해시, 실제 코드, 도구 권한, Git 상태를 대조해. 기록과 다르면 알리고 기존 파일은 보존해.
2. 이미 끝난 단계를 무조건 반복하지 마. 이전 PASS(Windows B1/B2, Claude의 로컬 PG SQL 검증)를 새 코드의 PASS로 옮겨 쓰지 마.
3. 고정 R02·10분·선행 실패 5건 이상·동일 시각 제외, 8테이블, 원본 복합키·전체 복합 FK, 계산 상태, SC03(첫 INSERT 뒤 없는 고객 SELECT 실패), 함수 200줄, 공개/비공개 범위를 지켜.
4. `REVIEW_STATE.json`의 `next_action`부터 수행하고, 끝날 때 기록 4종(변경보고서·인계·상태·재개)과 체크포인트 ZIP·외부 체크섬을 갱신해.
5. 비밀번호·PAT·쿠키·runtime.json·실제 운영자 정보는 요구하거나 기록하지 마.

본인 Windows 환경에서 이어갈 때의 첫 명령(저장소 루트):

```cmd
python scripts\verify-manifest.py --json
python scripts\package-source.py --dry-run --json
```
