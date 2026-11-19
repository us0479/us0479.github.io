# BankWatch 최종 제출 세트 v1.11

팀 BankWatch / 이우석 / 2022112052. 문서 제출일 표기2026-11-29. 실제 제출은 사용자가 LMS에서 수행한다.

## 제출/실행에 사용할 파일

- submission/BankWatch_user-scenario.md (v1.11), BankWatch_erd.pdf, BankWatch_spec.xlsx (두 파일 v1.10 내용 무변경). 버전 혼합은 의도되었으며 내용·키·ID가 같다.
- reference/BankWatch_data_dictionary.md: v1.10 그대로인 설계 근거.
- topic/BankWatch_topic_registration.txt: LMS 주제등록 온라인 텍스트. MD는 원문. 마감2026-11-20 23:59KST. 1차 문서2026-11-30 23:59, 최종 시연·소스2026-12-18 23:59KST.
- source/BankWatch-v1.11-public-source.zip: 실행 코드를 모두 포함한 공개판. 비공개 검증 원문은 제외한다. CP3 원본 SHA dc02a5ee4d03147dc6f041ac812925c6ce10376466018569815a1da6ccd72d3a.
- presentation/BankWatch_Claude_Presentation_Prompt.md: Claude에 그대로 붙여넣는 발표 PPTX·대본 제작 요청. PRESENTATION_INPUT_GUIDE.md의 입력도 첨부한다. 현재 묶음에는 아직 제작하지 않은 PPTX·대본을 넣었다고 주장하지 않는다.

비공개 전체 세트에는 source/BankWatch-v1.11-private-source.zip과 audit/가 추가된다. 교수님께 전체 소스·원문 근거까지 전달할 때 비공개 전체 세트를 사용한다. 공개 묶음과 비공개 묶음은 용도가 다르며 공개판이 구현을 생략한 버전은 아니다.

## 최종 기준선과 이번 변경

Claude CP3의 Java·SQL·UI·샘플·Maven runtime37은 실제 Windows B1/B2 실행 코드와 같다. 시나리오 개정, 등록문과 검토·인계 기록, 감사 도구, 포장·메타데이터 수정을 반영했다. ERD·명세·데이터 사전은 수정하지 않았다.

Codex는 private C1 보존 소스에 CP3의 허용 파일을 병합했다. private README·manifest·검증기·패키저는 공개판으로 덮어쓰지 않고, schema1.5에서 C1 schema1.4 원문 전체를 byte-exact 보존한 채 현재 파일을 검증하도록 갱신했다. runtime 코드는 변경하지 않았다. CHANGELOG_FINAL.md에서 구체적인 차이를 확인한다.

실제 Windows 최종 ZIP 바이너리(8d00e392…)는 이번 Cloud에 제공되지 않았다. Cloud의 보존 C1 소스와 Windows C1/B1/B2 증거를 사용한 재조립이며 사용자 PC의 미커밋 작업을 직접 변경한 것이 아니다. Windows 원본 폴더에서 무조건 git pull하거나 ZIP을 덮어쓰지 말고 먼저 로컬 변경을 보존·비교한다. 새 확인용 폴더에는 아래처럼 별도 clone할 수 있다.

```cmd
cd /d C:\Users\us021\source\bankwatch-learning
git clone https://github.com/us0479/bankwatch-db.git bankwatch-db-final-v111
cd bankwatch-db-final-v111
git rev-parse HEAD
python scripts\verify-manifest.py --json
```

명령의 Python·Java·DB 준비는 본인 환경에 맞게 하고 README의 초기화/운영자 생성/앱 실행 순서를 따른다. 기존 DB를 삭제하지 않는다.

## 검증과 배포를 구분

FINAL_VALIDATION.json은 이번 Cloud CP3 검토·Java21 빌드·전용 PostgreSQL 통합시험10·로컬 Pages35검사의 결과와 보존된 Windows JUnit10/HTTP48·132검사의 출처를 구분한다. 이번에 Windows HTTP를 다시 실행하지 않았다. “시험 수”와 “요청 수”, “assertion 수”를 합산하지 않는다.

최종 private 소스 main 커밋: c8092b23d475bf501f69f52f253350dbd74d9aa1.
홈페이지 최종 다운로드: https://us0479.github.io/bankwatch/downloads/BankWatch-v1.11-final-public.zip
Pages 배포와 원격 파일 확인 결과는 ZIP 밖 bankwatch/deployment-receipt.json으로 확인한다(배포 커밋의 자기참조를 피함). 사이트는 브라우저 가상 체험판이며 Java/PostgreSQL 서버가 아니다.

## 발표 준비

총600초 안에 도메인 선택 이유→DB가 필요한 업무→요구의 실제 구현→검증을 설명한다. 본문9장570초와 전환여유30초이며80초 시연이 이미 포함되어 있다. 교수 피드백에 따른 사후 서버 탐지·R02단일화·두 상태도·함수200줄을 반드시 설명한다. 실제 낭독 시간은 사용자가 리허설로 측정해야 한다.

기존 CP1/CP2 후보와 구버전 ZIP은 이 활성 세트에서 제외했다. 감사 원본과 Git 과거 이력은 보존한다. LMS 제출·본인PC 적용·실제10분 발표 리허설은 자동 완료되지 않는다.
