# DB 마이그레이션 운영 및 검증

KAN-28은 KAN-27의 병합된 저장 모델을 Flyway로 생성한다. 초기 파일은 `src/main/resources/db/migration/V1.1.0.001__init.sql`이다. 버전은 Jira에서 정한 스프린트 규칙이며 다음 파일은 `V1.1.0.002__description.sql`처럼 작성한다.

## 기동 순서

1. Spring Boot의 Flyway starter가 DB에 연결한다. core는 starter가 포함하며 MySQL 지원은 flyway-mysql을 사용한다.
2. 빈 스키마에 이력 테이블과 8개 도메인 테이블, 인덱스, FK/UNIQUE/CHECK를 생성한다.
3. project/lab/card 카테고리를 초기화한다. 고정 숫자 ID를 API 계약으로 사용하지 않는다.
4. Hibernate가 8개 엔티티의 컬럼 매핑을 `ddl-auto: validate`로 검증한다.
5. 재기동에서는 적용 이력과 체크섬을 검증하고 이미 적용된 버전을 재실행하지 않는다.

`application.yml`의 validate 설정은 local/prod 모두 상속한다. 로컬 개인 설정에 update/create/create-drop이 있으면 제거해야 한다. 테스트 역시 validate를 사용하며 Hibernate가 스키마를 대신 만들어 주지 않는다.

## MySQL 전제

- MySQL 8.0.16 이상을 사용한다(CHECK 제약 강제 지원 필요). 로컬 검증은 8.0.35로 수행했다.
- InnoDB, utf8mb4, `utf8mb4_0900_as_cs`로 테이블을 생성한다. 원본 ID의 대소문자 차이를 유지하기 위해 명시적으로 case-sensitive collation을 사용한다. 이름 검색의 대소문자 정책은 기능 구현 시 별도로 정한다.
- SQL의 `${tableOptions}`는 MySQL의 engine/charset/collation만 치환한다. H2 MySQL 모드에서는 빈 값이다. 스키마 SQL을 테스트용으로 복제하지 않는다.
- 앱이 사용할 **빈 개발 DB**를 만들고 application-local.yml에 연결한 뒤 `./gradlew bootRun`을 실행한다. DB 계정은 해당 스키마의 테이블/인덱스/제약 생성 및 DML 권한이 필요하다.
- 현재 로컬/운영 DB에 자동으로 접속해 적용하지 않았다. 운영 적용 전 DB 버전과 아래 기존 스키마 점검이 필요하다.

## 이미 데이터가 있는 DB

`baseline-on-migrate: false`, `clean-disabled: true`를 유지한다. 이력 없이 테이블이 존재하면 자동 기동을 실패시켜 기존 데이터를 보호한다. 실패를 없애려고 baseline을 켜거나 Flyway 이력/테이블을 지우지 않는다.

운영 반영 전 백업과 복원 확인, 현재 테이블·데이터·엔티티 비교를 수행한다. 기존 테이블이 있으면 이 초기 SQL을 그대로 적용하지 않고, 실제 상태에 맞는 별도 전환 절차와 데이터 이관을 리뷰해야 한다. 특히 MySQL DDL은 파일 전체를 하나의 트랜잭션으로 롤백하지 못하므로 중간 실패 시 실제 생성된 객체와 이력을 조사한 뒤 복구한다. 운영 DB 버전·기존 데이터 조사는 이 PR의 로컬 검증에 포함되지 않는다.

적용된 파일은 수정하지 않고 새 버전을 추가한다. flyway_schema_history를 직접 수정하거나 자동 repair를 호출하지 않는다.

## 엔티티 범위와 미정 사항

이번 엔티티는 스키마 검증을 위한 최소 영속 매핑이다. 도메인별 entity 패키지에 두며 FK는 Long ID로 매핑한다. SQL이 참조 무결성과 삭제 정책을 담당한다. 연관 조회 전략, 생성/수정 메서드, Repository/API는 각 기능 티켓에서 추가한다. 감사 컬럼은 JPA lifecycle에서 UTC로 작성하며 동기화 SQL 직접 쓰기에서도 UTC 시각을 지정해야 한다.

연결된 카테고리와 source의 의미 일치, VIEWER 응답 필터, 1차 API 시간 입력 거부는 서비스 계층의 책임이다. SQL CHECK는 날짜·시간 조합과 원천 FK 조합을 보장하지만 권한을 대신 구현하지 않는다.

KAN-27을 그대로 따르므로 submission_type은 NOT NULL이다. API PR #6에서 선택값 여부가 합의되면 후속 마이그레이션으로 변경한다. 구글 usage_type의 허용값과 원본 ID 생성 방식은 미정이므로 임의의 ENUM/자동 생성 규칙을 넣지 않았다. 카드 테이블 생성은 구글 연동 구현 완료를 뜻하지 않는다.

## 테스트

Java 17에서 기본 테스트는 H2를 사용한다.

```powershell
.\gradlew.bat test bootJar --no-daemon
```

MySQL 테스트는 아래 이름의 **빈 전용 DB**를 별도로 만든 뒤 환경변수로 지정한다. 기존 개발/운영 스키마를 지정하지 않는다. 테스트는 첫 기동 시 마이그레이션과 시드를 커밋하고, 무결성 검증 데이터는 각 테스트가 롤백한다.

```powershell
$env:MIGRATION_TEST_URL = 'jdbc:mysql://127.0.0.1:3306/kan28_test?serverTimezone=UTC'
$env:MIGRATION_TEST_DRIVER = 'com.mysql.cj.jdbc.Driver'
$env:MIGRATION_TEST_USER = 'your_test_user'
$env:MIGRATION_TEST_PASSWORD = 'your_test_password'
$env:MIGRATION_TEST_TABLE_OPTIONS = 'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs'
.\gradlew.bat test --rerun-tasks --no-daemon
```

이 환경변수는 테스트 리소스에서만 사용하며 운영 설정에는 없다. H2로 돌아갈 때는 이 환경변수를 해제하거나 새 터미널을 연다. 테스트는 원천별 중복, 대소문자 구별, 날짜/시간 오류, FK 삭제 제한, 참석자/오류 이력 CASCADE, 로그 카운터 범위를 검사한다. MySQL CHECK 오류(HY000/3819)와 H2 CHECK 오류(23513)를 구분한다.

`.github/workflows/verify.yml`은 develop/main 대상 PR에서 H2 및 MySQL 8.0 검증을 실행한다. 브랜치 보호의 필수 검사 지정은 저장소 설정에서 별도로 해야 한다.

참고: [Spring Boot DB 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [KAN-28](https://jjhong12122.atlassian.net/browse/KAN-28).
