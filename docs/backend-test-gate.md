# KAN-66 백엔드 테스트와 배포 게이트

## 요구사항과 검증 위치

| 요구사항 | 기존/보완 테스트 |
| --- | --- |
| 리드타임 역산, 0일, 월·연도 경계, 윤년 | ProjectScheduleTests, PreparationScheduleTests |
| D-Day와 서울 자정 경계 | ProjectScheduleTests, ProjectDeadlineApiTests |
| 시트·고정 A~D열·날짜·참석자 파싱 | ExcelLedgerReaderTests, LedgerSheetSelectorTests, LedgerRowParserTests |
| 자동 일정 반복 생성 방지 | PreparationScheduleTests.runningTheBatchAgainChangesNothing |
| 재업로드 ID 유지·재활성화·오류 월 보존 | LedgerSyncServiceTests |
| 반영 롤백·동시 요청 직렬화 | LedgerSyncServiceTests |
| 주요 API 및 권한 | Auth/Authorization/Project/Event/Member/Category API 테스트 |
| 배포의 검증 의존성, 실패 무시 방지 | DeploymentGateTests |

KAN-59·60은 별도 리뷰 중이며 이 브랜치에는 포함하지 않았다. 머지 후 그 테스트도 동일한 전체 테스트 명령에 자동 포함된다.

## 실행 흐름

- develop/main 대상 PR과 develop push: Verify Backend 실행.
- main push: Deploy Backend의 verify 작업이 동일한 verify.yml을 호출한다.
- H2 전체 테스트와 bootJar, 이어서 실제 MySQL 8 전체 테스트를 실행한다. MySQL 테스트는 --rerun-tasks로 H2 결과를 재사용하지 않는다.
- build-and-deploy는 needs: verify로 묶여 있다. 검증이 실패하거나 취소되면 GHCR 로그인·이미지 빌드/푸시·EC2 배포 작업은 실행되지 않는다. 테스트 실패를 무시하는 설정은 없다.
- 검증 작업은 저장소 읽기 권한만 사용하고 패키지 쓰기 권한은 배포 작업에만 부여한다.
- H2와 MySQL 보고서를 별도 아티팩트로 7일 보관한다. 테스트 실패 시에도 해당 보고서 업로드를 시도한다. 검증 시간 제한은 15분이다.

Dockerfile의 bootJar -x test는 유지한다. 배포 워크플로는 같은 커밋의 검증을 먼저 통과해야 Docker 빌드를 시작하므로 테스트를 이미지 안에서 반복하지 않는다. 수동 Docker 빌드 자체는 CI 게이트를 제공하지 않는다.

## 검증 범위와 운영 확인

DeploymentGateTests는 워크플로 YAML을 읽어 검증 의존성 제거·실패 무시 조건의 도입을 감지한다. 이는 설정 회귀 테스트이며 GitHub 스케줄러나 실제 운영 배포를 실행하는 테스트는 아니다. PR CI로 공통 워크플로의 H2·MySQL 실행을 확인하고, main 배포의 needs 의존성은 코드 리뷰한다. 검증 목적으로 운영 배포나 의도적인 main 실패 커밋은 만들지 않는다.

브랜치 보호 규칙(실패 PR의 머지 차단)은 저장소 설정이며 이번 배포 게이트와 별개다. 이번 변경은 해당 규칙을 변경하지 않는다.

로컬: `./gradlew test bootJar --no-daemon` (Windows는 `gradlew.bat`). MySQL 검증에는 기존 MIGRATION_TEST_URL/DRIVER/USER/PASSWORD/TABLE_OPTIONS 설정을 사용한다. 테스트 DB는 운영 DB와 분리한다.
