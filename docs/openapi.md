# API 문서 기본 설정 (KAN-30)

로컬 서버 실행 후 http://localhost:8080/swagger-ui.html 에 접속합니다.
프론트 서버(5173)가 아닌 백엔드 서버(8080)의 주소입니다.

- OpenAPI JSON: `/v3/api-docs`
- backend 그룹 JSON: `/v3/api-docs/backend` (`/api/**`만 포함)
- 현재 구현된 API만 자동으로 표시됩니다. 계획 중인 CRUD API는 아직 표시하지 않습니다.
- 문서의 Try it out은 실행 중인 서버로 실제 요청을 보냅니다.

`prod` 프로파일에서는 Swagger UI와 OpenAPI JSON을 모두 비활성화합니다.
운영 문서 공개가 필요하면 인증·접근 제한 정책을 먼저 정하고 별도로 변경합니다.

Spring Boot 4용 springdoc 3.1.1을 사용합니다.
공식 호환성 안내: https://springdoc.org/faq.html

이번 변경은 의존성, 경로, 메타데이터, 그룹과 운영 노출 설정만 포함합니다.
KAN-29 병합 후 공통 성공/오류 응답 스키마와 상세 예제를 별도로 반영·검증합니다.
