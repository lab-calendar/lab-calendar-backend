# KAN-60 업로드 이력 조회

`GET /api/card-expenses/imports?limit=20`

EDITOR만 조회한다. 미인증은 401, VIEWER는 403이다. limit은 기본 20, 허용 범위 1~100이며 잘못된 값은 400이다. 응답은 `{ "data": [...] }`, 이력이 없으면 빈 배열이다.

KAN-58에서 기록한 MANUAL 이력을 started_at 내림차순, 같은 시각이면 id 내림차순으로 조회한다. 기존 스키마에는 수동 구글 동기화와 엑셀 업로드를 구분하는 필드가 없으므로, 이전 MANUAL 이력이 있다면 함께 반환된다. SCHEDULED 이력은 제외한다. 미리보기는 이력을 만들지 않는다.

각 항목:

| 필드 | 의미 |
| --- | --- |
| id | 문자열 ID |
| fileName | 저장된 source_document_id (기존 수동 동기화는 문서 식별자일 수 있음) |
| status | RUNNING / SUCCESS / PARTIAL / FAILED |
| startedAt, finishedAt | 저장된 로컬 날짜·시각, 종료 전 finishedAt은 null |
| durationMs | 소요 밀리초, 종료 전 null 가능 |
| processed, added, updated | 저장된 처리·추가·수정 수 |
| removed | 비활성화 수 |
| skippedRows | ERROR 행 수, WARNING 수와 다름 |
| errorCode | FAILED이면 IMPORT_FAILED, 나머지는 null |
| problemCount | 해당 이력의 전체 문제 건수 |
| problems | 기록 순으로 최대 100개, locator / code / level |

`locator`는 `시트 이름:행 번호`다. KAN-58의 문제 코드만 그대로 반환하고 알 수 없는 코드는 UNKNOWN이다. WARNING 표식만 WARNING으로 반환하고 나머지는 ERROR로 취급한다. DB의 자유 형식 error_message/message와 source_record_id는 노출하지 않는다. 실패 이유는 고정 코드로 표시한다.

프론트는 problemCount가 problems.length보다 크면 일부만 표시됐음을 안내한다. 전체 오류 내려받기와 페이지 이동은 이번 범위에 없다. 목록과 문제 행은 읽기 전용 트랜잭션에서 두 번의 일괄 조회로 읽으며 데이터를 변경하지 않는다.

KAN-59의 POST와 다른 컨트롤러에 같은 경로의 GET으로 구현했으므로 KAN-59 머지 전에도 develop 기준으로 독립 리뷰할 수 있다. DB 변경은 없다.

검증: 권한, 빈 이력, 동일 시각 정렬, 결과별 건수, 실패 원문 비노출, 문제 목록 상한, 기본/잘못된 limit, 조회 후 이력 불변을 실제 DB와 MockMvc로 검증한다. 테스트 DB는 CI의 MIGRATION_TEST_URL을 따라 H2와 MySQL 모두 실행한다.
