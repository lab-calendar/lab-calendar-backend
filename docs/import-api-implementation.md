# KAN-59 업로드 API 구현

현재 KAN-58 기반 초안이며 KAN-56~58 리뷰 후 재검증한다. UI는 KAN-61, 이력 GET은 KAN-60이다.

## 호출과 응답

EDITOR 세션으로 `POST /api/card-expenses/imports`, multipart/form-data의 file에 xlsx를 보낸다. dryRun 쿼리는 기본 true이며 미리보기는 업무 데이터·이력을 저장하지 않는다. 실제 반영은 dryRun=false와 multipart previewToken 문자열, 동일한 파일 바이트가 필요하다. FormData를 쓰는 브라우저는 Content-Type을 직접 고정하지 않는다.

성공 HTTP 200과 `{data: ...}`를 반환한다. data는 dryRun, previewToken, fileName, months, skippedSheets, problems, totals를 포함한다. months의 월은 YYYY-MM, 상태는 READY/BLOCKED다. 반영 응답의 previewToken은 null이며 재발급하지 않는다. 전부 BLOCKED여도 미리보기는 200으로 오류 목록을 제공하고 반영은 422 NO_APPLICABLE_MONTHS로 거부한다.

READY 빈 월은 제거 예정 건수를 표시하며 실제 확인 후 기존 내역을 비활성화한다. BLOCKED 월은 변경 건수가 모두 0이고 기존 업무 데이터를 보존한다. totals는 READY만 합산하며 skippedRows는 ERROR 행 수, blockedMonths는 보류 월 수다.

## 토큰 및 동시성

- 10분 만료, 현재 시각이 만료 시각 이상이면 409 PREVIEW_STALE. 파서 버전 ledger-v1, 파일 SHA-256, 대상 월 목록 해시, DB 상태 해시를 서명한다.
- HMAC-SHA256에는 기존 AUTH_TOKEN_SECRET을 쓰되 `card-import-preview:` 용도 접두사로 세션 토큰 서명과 분리한다. 키 회전 시 기존 미리보기는 무효화된다. 원문 이름·파일 내용은 토큰에 없다.
- 토큰 누락/형식 오류/서명 위조는 400 PREVIEW_TOKEN_REQUIRED 또는 PREVIEW_TOKEN_INVALID. 올바르게 서명된 토큰의 만료·파일·월·DB·파서 버전 불일치는 409다.
- DB 지문은 대상 월의 비활성 내역 포함 업무 필드, 내역·일정·참석자 ID와 연결 상태를 정렬하여 JSON 직렬화한다. 필드 경계와 null을 보존한다. last_seen_at 등 감사 시각은 제외하므로 업무 상태가 그대로인 토큰은 재사용 가능하다. 첫 반영으로 업무 상태가 바뀌면 기존 토큰은 409이며 새 미리보기가 필요하다.
- 미리보기 차이와 지문을 동일 DB 트랜잭션에서 읽는다. 파일 재파싱·토큰/지문 검증부터 apply의 커밋 완료까지 KAN-58과 같은 JVM 잠금을 유지한다. 별도 업로드가 검증과 반영 사이에 끼어들 수 없다.
- 다중 인스턴스 운영에는 DB 기반 락이 필요하다. 현재는 단일 JVM 전제다.

## 입력·오류

- 파일 상한 5 MiB(5,242,880바이트), multipart 전체 6 MiB. multipart 메모리 임계값도 6 MiB로 두어 허용 파일을 임시 디스크에 쓰지 않게 한다. 프록시 제한은 배포 환경에서 별도 확인한다.
- 읽기 실패·머리글·중복 월 등 400, 크기 초과 413, 미인증 401, VIEWER 403. DB 연도 제한은 1000~9999다.
- 반영 중 실패는 500이며 업무 변경 롤백 후 실패 이력을 별도 저장한다. 입력·권한·토큰 거부와 모든 월 보류는 반영 이력을 생성하지 않는다.
- 파일 이름은 경로·제어문자를 제거하고 191자 초과는 거부한다. 동일 바이트의 파일명 변경은 허용하며 반영 요청의 정제한 이름을 이력에 쓴다.
- 오류 응답은 최상위 code/message/fieldErrors. 원문 셀·참석자 이름·내부 예외를 오류나 토큰에 넣지 않는다. problems에는 시트·행·등급·코드와 고정 안내 문구만 담는다.
- 파일을 먼저 검증하므로 잘못된 파일과 토큰 오류가 겹치면 파일 오류가 먼저 나올 수 있다. 프레임워크의 multipart 제한 오류도 같은 API 오류 형식과 HTTP 413을 사용한다.

## 검증과 후속

CardImportApiTests는 미리보기/반영·멱등성·파일 변경·다른 업로드 후 충돌·참석자/비활성 내역 변경·부분 성공·빈 월·모든 월 보류·권한·입력 오류·실제 HTTP multipart 제한·10분 만료 경계·파서 버전을 검증한다. KAN-58 동시성/롤백 테스트도 전체 테스트에 포함한다.

KAN-57 파싱 또는 KAN-58 정규화/반영 규칙 변경 시 VERSION을 올리고 재검증한다. source=GOOGLE_SYNC 호환 정책은 유지하며 CARD_IMPORT 전환은 프론트와 함께 진행한다. 운영 MySQL·프록시·다중 서버 검증과 업로드 UI는 아직 수행하지 않았다.
