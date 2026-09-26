# KAN-56 엑셀 읽기 기초

KAN-54 리뷰 중 독립적으로 준비하는 읽기 계층이다. 업로드 API·DB 저장·날짜 이어받기·월 식별·머리글 검증·인원 파싱은 아직 구현하지 않는다.

`ExcelLedgerReader.read(fileName, bytes)`는 시트 이름, 실제 행 번호(1부터), A~D 네 칸의 표시 문자열과 오류 셀 여부를 반환한다. 누락 셀은 빈 문자열이다. XML에 없는 행은 생성하지 않으며, 빈 시트와 연도 없는 시트도 읽은 그대로 반환한다. 후속 파서가 업무 규칙을 적용한다. 결과를 로그에 출력하지 않는다.

Apache POI 5.5.1의 XSSFWorkbook과 DataFormatter를 사용한다. 수식은 재계산하지 않고 저장된 값만 읽는다. 저장값 없는 수식은 FORMULA_CACHE_MISSING으로 거부하고 오류 셀은 표시값과 error=true를 유지한다. E열 이후 값은 반환하거나 해석하지 않는다. 다만 POI는 파일을 열 때 전체 통합문서를 메모리에 로드한다. 선택 열만 물리적으로 읽는 스트리밍 구현은 이 단계 범위 밖이다.

## 입력과 한계

- 확장자와 실제 통합문서 유형 모두 xlsx여야 한다. xls·xlsm은 지원하지 않는다.
- 파일 최대 5 MiB, 시트 최대 200개, 실제 존재 행 합계 최대 100,000개. 후자의 두 제한은 POI 로드 후 적용되므로 메모리 사용량의 사전 상한은 아니다.
- POI의 ZIP 방어 기본값을 변경하지 않는다. 공개 업로드 API 연결 시 multipart 제한과 압축 해제 자원 상한을 함께 검토해야 한다.
- 잘못된 파일은 코드만 담은 ExcelReadException으로 변환한다. 원본 이름·내용·POI 예외 메시지는 외부에 반환하지 않는다. HTTP 상태 연결은 KAN-59 작업이다.
- 실제 회의록은 저장소나 테스트에 넣지 않는다. 테스트는 메모리에서 가상 워크북을 생성한다.

## 직접 확인

IntelliJ에서 `ExcelLedgerReaderTests`를 열고 클래스 왼쪽 실행 버튼을 누른다. `readsSheetNamesFourColumnsAndOriginalRowNumbers`가 가장 작은 사용 예제다. 전체 테스트는 `gradlew.bat test`, 읽기 테스트만 실행하려면 `gradlew.bat test --tests '*ExcelLedgerReaderTests'`.

KAN-54 합의 후 시트 연월·중복 월·A~D 머리글 검증을 추가해야 KAN-56 전체가 완료된다. 월 반영 정책 및 업로드 엔드포인트는 이 기초 PR에 포함하지 않는다.

참고: [POI 배포](https://poi.apache.org/download.html), [DataFormatter](https://poi.apache.org/apidocs/dev/org/apache/poi/ss/usermodel/DataFormatter.html)
