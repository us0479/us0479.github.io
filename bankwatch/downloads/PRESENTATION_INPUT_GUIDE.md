# 발표 제작에 넘길 자료

기준: BankWatch / 이우석 / 2022112052. 총10분(시연 포함), 본 내용9분30초+전환여유30초.

Claude에는 **BankWatch_Claude_Presentation_Prompt.md 전체**를 새 대화의 첫 요청으로 붙여넣는다. 별도 프로젝트 지침은 “첨부된 최종 기준선과 실행/미실행 구분을 유지하고, 총600초 안에 PPTX·대본·검토표를 실제 제작한다”로 짧게 두어도 된다.

## 첨부할 파일

1. 최신 최종 묶음의 FINAL_README.md, FINAL_VALIDATION.json, SHA256SUMS.txt — 이번 확정 범위와 해시.
2. source/BankWatch-v1.11-public-source.zip — Java/SQL/UI/테스트 원문과 공개 검증 요약. ZIP 열기가 불가능하면 PC에서 풀어 docs/4문서, db/SQL3개, Java 소스, 샘플과 README를 첨부한다. 검토가 필요한 실제 파일을 읽지 못한 상태로 발표 구현을 추측하게 하지 않는다.
3. submission/BankWatch_user-scenario.md, BankWatch_erd.pdf, BankWatch_spec.xlsx 및 reference/BankWatch_data_dictionary.md — ZIP 안에도 있으나 별도 첨부하면 읽기 쉽다.
4. 공식 database-term-project-v02.pptx, 최신 공지/피드백과 BankWatch_01_Implementation_Plan.md, BankWatch_02_Data_and_Verification.md — 원본을 보관한 위치에서 추가한다. 오래된 안내와 최신 공지가 다르면 최신 공지·사용자의 확정 계약 우선.
5. 필요하면 실제 앱 화면 캡처·시연 링크. Pages https://us0479.github.io/bankwatch/ 는 정적 가상 체험판이다. 실제 Java/PostgreSQL 실행은 로컬 앱이다.

원문 Windows 검증로그·private 전체 ZIP은 다른 사람 Claude 계정에 반드시 올릴 자료가 아니다. 공개 요약과 현재 코드만으로 충분한 부분을 먼저 제작한다. 특정 원문이 정말 필요하면 필요한 범위를 정해 본인 환경에서만 처리한다. 비밀번호·PAT·.env는 첨부하지 않는다.

## 제작 후 본인이 확인할 것

- PPTX와 PDF를 모두 열어 한글/도형/페이지 잘림 확인.
- 화면 시연80초를 포함해 실제로 한번 읽고600초 안인지 측정. 지연 여유30초는 새 내용으로 채우지 않는다.
- “도메인 선택→DB가 필요한 문제→요구가 구현된 방식→SQL/트랜잭션 근거”가 들리는지 확인.
- 검증 수치가 어느 환경/버전인지, Pages가 서버 앱처럼 설명되지 않는지 확인.
- 다른 사람 계정의 채팅 삭제 전 PPTX·대본·PDF·검토표·ZIP·체크섬을 내려받아 실제 열리는지 확인. 채팅 삭제만으로 프로젝트 지식 첨부가 삭제된다고 가정하지 말고 본인이 추가한 프로젝트 파일도 확인한다.
