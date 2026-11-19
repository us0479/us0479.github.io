# us0479 개인 홈페이지

https://us0479.github.io/ — 기존 소개·탐색을 유지한 개인 학습 기록입니다.

## BankWatch

- 브라우저 체험: https://us0479.github.io/bankwatch/
- 공개용 전체 소스: [BankWatch-v1.10-public-source.zip](bankwatch/downloads/BankWatch-v1.10-public-source.zip)
- 소스 내용: [bankwatch/source](bankwatch/source)
- 실제 앱 검증 요약: [verification-summary.json](bankwatch/verification-summary.json)
- 제출 문서/체크섬: [bankwatch/downloads](bankwatch/downloads)

체험판은 가상 데이터와 브라우저 저장소로 동작합니다. 실제 계정 인증·Java 서버·PostgreSQL 잠금/교착을 실행하지 않습니다.
실제 앱은 Windows의 Spring Boot4.1.2/Java21과 별도 PostgreSQL16.16에서 통합 테스트10/인증HTTP48/132검사를 통과했습니다.

공식 문서 v1.10 / 공개판 v1.10-public.1입니다. 공개 소스의 runtime37 파일은 검증본과 같습니다.
공개 패키지는 비공개 원문 검증 로그·비밀번호·.env·DB파일을 제외하므로 Windows 제출 원본 ZIP과 해시가 다릅니다.
원본 제출 자료와 비공개 us0479/bankwatch-db 저장소는 이 배포에서 수정하지 않습니다.

## 로컬 정적 미리보기

    python -m http.server 8000

http://127.0.0.1:8000/ 으로 접속합니다. 실제 Java 앱의 실행 안내는 [소스 README](bankwatch/source/README.md)에 있습니다.
