# API 문서 (KAN-30)

로컬 백엔드 실행 후 http://localhost:8080/swagger-ui.html 에 접속합니다.
프론트(5173)가 아닌 백엔드(8080)의 주소이며, DB 연결 설정은 기존 로컬 설정을 사용합니다.

- 기본 OpenAPI JSON: `/v3/api-docs`
- backend 그룹: `/v3/api-docs/backend` (`/api/**`만 포함)
- 현재 구현된 API만 표시합니다. 미래의 일정/과제 API나 인증 기능은 문서에 만들어 넣지 않습니다.
- Try it out은 실제 서버에 요청을 보내므로 쓰기 API에서는 실제 데이터가 바뀔 수 있습니다.

## 운영 정책

`prod`에서는 Swagger UI와 OpenAPI JSON을 모두 비활성화합니다.
운영 문서가 필요하면 인증·접근 제한 정책을 정한 뒤 별도로 변경합니다.
운영 비활성화 테스트는 UI, 기본/그룹 JSON, swagger-config가 모두 404인지 검증합니다.

## 응답과 API 추가 방법

KAN-29의 `ApiResponse<실제응답DTO>`를 반환 타입에 명시하면 data 안의 DTO가
자동으로 스키마에 표시됩니다. 목록은 `ApiResponse<List<실제응답DTO>>`를 사용합니다.
raw ApiResponse나 Object를 반환하면 실제 데이터 구조를 문서에서 알 수 없습니다.
201/204 등 상태는 `@ResponseStatus` 또는 OpenAPI 응답 어노테이션으로 명시합니다.
본문 없는 응답을 직접 문서화할 때는 `content = @Content`를 사용합니다.

컨트롤러에는 `@Tag`, 메서드에는 `@Operation(summary = "...")`를 추가합니다.
Health 태그의 `/api/health`는 기존 `{"status":"ok"}` 응답과 예제를 유지합니다.

공통 오류 스키마는 `ApiError`이고 모든 ErrorCode의 안전한 예제가
`components.responses`에 등록됩니다. 예상치 못한 500은 기본/그룹 문서 모두에 추가하며,
기존에 명시한 500 응답은 덮어쓰지 않습니다. 나머지 오류는 실제 발생 가능한 API에만 지정합니다.

```java
@ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
@ApiResponse(responseCode = "404", ref = "#/components/responses/NOT_FOUND")
```

위 어노테이션은 `io.swagger.v3.oas.annotations.responses.ApiResponse`입니다.
성공 래퍼와 이름이 같으므로 필요한 경우 전체 패키지명을 적습니다.
잘못된 JSON은 INVALID_REQUEST, DTO 검증 실패는 VALIDATION_FAILED이며,
동일한 상태의 오류가 여러 개라면 해당 API 설명에 조건을 함께 적습니다.

전역 Advice의 동적 상태 코드를 자동 추론해 성공 스키마를 오염시키지 않도록
`springdoc.override-with-generic-response=false`로 설정했습니다.
이 설정은 문서 생성에만 영향을 주며 KAN-29의 실제 예외 처리 동작은 유지됩니다.

## 검증과 참고

`./gradlew test bootJar`로 기존 API 계약/마이그레이션과 문서 생성 테스트를 실행합니다.
문서 테스트의 단일/목록/204 컨트롤러는 테스트 전용이고 배포 코드에 포함되지 않습니다.

Spring Boot 4용 springdoc 3.1.1을 사용합니다.
공식 설정: https://springdoc.org/
그룹 문서에도 적용되는 GlobalOpenApiCustomizer: https://springdoc.org/faq.html
