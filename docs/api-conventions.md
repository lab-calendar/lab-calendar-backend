# API 구현 규칙 (KAN-29)

## 패키지

기준 패키지는 `com.labcalendar.labcalendarbackend`입니다. 기능별로
`domain.<기능>.controller`, `service`, `repository`, `dto`, `entity`를 사용합니다.
컨트롤러는 HTTP 입출력과 검증, 서비스는 업무 규칙과 트랜잭션, repository는
DB 접근을 담당합니다. 기존 entity 패키지를 유지하고 필요한 계층부터 추가합니다.
JPA 엔티티를 직접 응답하지 않고 요청/응답 DTO를 분리합니다.
공통 응답은 `common.api`, 예외는 `common.exception`에 둡니다.

## 성공 응답

업무 API는 `ApiResponse.of(responseDto)`로 명시적으로 한 번 감쌉니다.
단일 객체는 `{"data":{...}}`, 목록은 `{"data":[...]}`이며 빈 목록은 `[]`입니다.
생성은 `ResponseEntity.status(201).body(ApiResponse.of(responseDto))`,
삭제는 `ResponseEntity.noContent().build()`를 사용합니다. 204에는 본문이 없습니다.
기존 프론트 연결 확인용 `GET /api/health`는 호환성을 위해 `{"status":"ok"}`를 유지합니다.
응답을 자동으로 감싸는 기능은 없으므로 새 컨트롤러에서 래퍼를 명시해야 합니다.

## 에러 응답

에러는 성공 래퍼 없이 다음 형태로 내려갑니다.

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 확인해 주세요.",
  "fieldErrors": {"title": "제목을 입력해 주세요."}
}
```

필드 에러가 없으면 `fieldErrors`는 `{}`입니다. 프론트는 HTTP 상태로 분류하고
`message`를 표시합니다. `code`는 안정적인 서버 분류값입니다.

| HTTP | code | 용도 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 잘못된 JSON, 타입, 필수 파라미터 누락 |
| 400 | VALIDATION_FAILED | 요청 DTO/파라미터 제약 위반 |
| 401 | UNAUTHORIZED | 인증 필요 |
| 403 | FORBIDDEN | 작업 권한 없음 |
| 404 | NOT_FOUND | 데이터 또는 경로 없음 |
| 405 | METHOD_NOT_ALLOWED | 지원하지 않는 HTTP 메서드 |
| 406 | NOT_ACCEPTABLE | 지원하지 않는 응답 형식 |
| 409 | CONFLICT | 현재 데이터 상태와 충돌 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 지원하지 않는 요청 형식 |
| 429 | TOO_MANY_REQUESTS | 요청 제한 |
| 500 | INTERNAL_ERROR | 예상하지 못한 서버 오류 |

서비스에서 예상한 업무 실패는 `throw new BusinessException(ErrorCode.NOT_FOUND);`처럼
발생시킵니다. 새 업무별 문구가 필요하면 ErrorCode에 공개 가능한 문구와 상태를 추가합니다.
임의의 예외 메시지나 SQL, 비밀번호, 스택을 응답에 넣지 않습니다.
예상하지 못한 오류는 서버에 기록하고 클라이언트에는 고정 문구만 반환합니다.
프레임워크 예외는 상태와 헤더(405의 Allow 등)를 유지하며 본문을 통일합니다.
서블릿 `/error` 경로에도 공통 포맷을 적용합니다.
인증 기능은 이번 범위에 없으며, 향후 보안 필터의 실패 응답도 ApiError를 사용해야 합니다.

## 입력 검증

요청 DTO에 Jakarta Validation의 `@NotBlank`, `@NotNull`, `@Min`, `@Max` 등을
선언하고 컨트롤러의 `@RequestBody` 인자에 `@Valid`를 붙입니다.
누락을 구분해야 하는 숫자는 `Integer` 등 참조형과 `@NotNull`을 사용합니다.
중첩 객체에는 `@Valid`를 붙이고 Java DTO 필드명을 JSON 필드명과 동일하게 유지합니다.
검증 문구는 사용자에게 공개할 고정 문구로 작성하며 입력값이나 비밀값을 삽입하지 않습니다.
동일 필드의 오류가 여러 개면 첫 문구 하나만 반환하므로 순서에 의존하지 않습니다.
객체 전체의 제약 위반은 공통 message로 안내합니다.

쿼리/경로 인자에는 `@RequestParam("days")`, `@PathVariable("id")`처럼 외부 이름을
명시하고 제약을 선언합니다. MVC 기본 메서드 검증을 사용하므로 컨트롤러 클래스에
`@Validated`를 붙이지 않습니다. 반환값 검증 실패는 클라이언트의 잘못이 아니므로 500입니다.
DB 조회가 필요한 중복/권한 검사는 서비스의 업무 예외로 처리합니다.

## 검증

`./gradlew test bootJar`로 MVC 응답 계약과 기존 DB 마이그레이션을 함께 검증합니다.
ApiContractTests의 컨트롤러는 테스트 전용이며 실제 서비스 엔드포인트로 등록되지 않습니다.
