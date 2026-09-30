# deploy/

Lightsail 인스턴스 한 대(2GB 플랜, 월 $12) 위에서 mysql + backend + nginx(프론트) + certbot을
Docker Compose로 띄우기 위한 배포 매니페스트. 별도 RDS는 쓰지 않는다 (KAN-77).

## 최초 셋업 (서버에서)

```bash
cp .env.example .env   # DB_*, CERTBOT_EMAIL, AUTH_* 채우기 (아래 "공용 비밀번호" 참고)
docker compose up -d mysql backend   # backend는 mysql healthcheck 통과 후 기동, Flyway가 스키마 생성
CERTBOT_EMAIL=$(grep CERTBOT_EMAIL .env | cut -d= -f2) ./init-letsencrypt.sh
docker compose up -d
```

이후 GitHub Actions가 각 레포의 `main` push마다 새 이미지를 pull 받아
`docker compose up -d backend` / `docker compose up -d nginx`로 갱신한다
(각 레포의 `.github/workflows/deploy.yml` 참고).

## DB 백업

RDS의 자동 백업이 없으므로 매일 `mysqldump`를 떠서 서버의 `~/mysql-backups`에 14일치 보관한다.

```bash
crontab -e
# 매일 새벽 4시
0 4 * * * $HOME/lab-calendar/deploy/backup-mysql.sh >> $HOME/mysql-backups/backup.log 2>&1
```

복원:

```bash
gunzip -c ~/mysql-backups/lab_calendar-YYYYMMDD-HHMMSS.sql.gz \
  | docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" lab_calendar'
```

> 백업이 같은 서버 디스크에 있으므로 인스턴스 자체가 사라지면 같이 사라진다. 중요한
> 데이터가 쌓이기 시작하면 Lightsail 자동 스냅샷(월 소액)을 켜거나 백업 파일을 밖으로 옮긴다.

## 파일 구성

- `docker-compose.yml` — mysql, backend, nginx(프론트 이미지), certbot 4개 서비스
- `backup-mysql.sh` — 일일 DB 백업 스크립트 (cron으로 실행)
- `set-auth-passwords.sh` — 공용 비밀번호를 화면에 안 보이게 입력받아 해시만 `.env`에 저장
- `nginx/nginx.conf` — 정적 파일 서빙 + `/api/` 리버스 프록시 + TLS. 호스트에서
  볼륨으로 마운트되므로, 이미지 재배포 없이 이 파일만 바꾸고 `docker compose up -d nginx`로 반영 가능
- `init-letsencrypt.sh` — 최초 인증서 발급용 부트스트랩 스크립트 (더미 인증서 →
  실제 인증서 발급 → nginx reload)
- `.env.example` — 필요한 환경변수 템플릿 (`.env`는 gitignore 대상)

---

## 공용 비밀번호 (KAN-34, KAN-37)

개인 계정은 없다. 랩실이 **비밀번호 두 개**를 공유한다.

| | 등급 | 쓰는 사람 |
| --- | --- | --- |
| 편집용 | `EDITOR` | 일정을 등록·수정하는 랩실 구성원 |
| 조회용 | `VIEWER` | 보기만 하는 외부 자문 위원. 카드/경비는 서버가 응답에서 제외한다 |

서버에는 **평문이 아니라 BCrypt 해시만** 둔다. `.env` 에도, 저장소에도 평문은 적지 않는다.

### 해시 만들기

서버에 Docker가 있으므로 별도 설치 없이 만들 수 있다.

```bash
docker run --rm httpd:alpine htpasswd -bnBC 10 "" '원하는비밀번호' | tr -d ':\n'
```

`$2a$10$...` 형태의 한 줄이 나온다. 편집용·조회용 각각 한 번씩 실행한다.

> 비밀번호에 `$` 나 공백이 들어갈 수 있으므로 **작은따옴표로 감싸서** 넘긴다.
> 명령 실행 기록이 남는 것이 신경 쓰이면 앞에 공백을 하나 두거나(`HISTCONTROL=ignorespace`)
> 작업 후 `history -c` 로 지운다.

### `.env` 에 넣기

```bash
AUTH_EDITOR_PASSWORD_HASH='$2a$10$...'
AUTH_VIEWER_PASSWORD_HASH='$2a$10$...'
AUTH_TOKEN_SECRET='openssl rand -base64 48 결과'
```

**작은따옴표를 반드시 붙인다.** 해시에 들어 있는 `$` 를 Compose가 변수로 해석해
값이 잘린 채 컨테이너로 들어간다.

### 잘 들어갔는지 확인

**두 단계를 모두 해야 한다.** 기동 확인만으로는 부족하다.

**1단계 — 기동.** 앱은 설정이 비었거나 두 해시가 같으면 기동을 거부한다.

```bash
docker compose up -d backend
docker compose logs --tail=30 backend
```

실패했다면 로그에 어느 값이 문제인지 찍힌다.

```
lab-calendar.auth.editor-password-hash 가 설정되지 않았습니다.
편집용과 조회용 비밀번호 해시가 같습니다. 조회 등급이 편집 권한을 갖게 됩니다.
lab-calendar.auth.token-secret 은 32자 이상이어야 합니다.
```

**2단계 — 실제 로그인.** 여기가 진짜 확인이다.

따옴표를 빠뜨려 해시가 `$` 에서 잘려 들어간 경우, 값이 비어 있지도 않고 서로 같지도
않으므로 **앱은 멀쩡히 기동한다. 대신 아무도 로그인하지 못한다.** 1단계만 보고 넘어가면
이 상태로 배포된다.

```bash
curl -s -X POST https://lab-calendar.cloud/api/auth/login \
  -H 'Content-Type: application/json' -d '{"password":"편집용비밀번호"}'
# {"data":{"authenticated":true,"tier":"EDITOR"}}

curl -s -X POST https://lab-calendar.cloud/api/auth/login \
  -H 'Content-Type: application/json' -d '{"password":"조회용비밀번호"}'
# {"data":{"authenticated":true,"tier":"VIEWER"}}
```

**두 번 다 실행해서 등급이 서로 다르게 나오는지 확인한다.** 401이 나오면 해시가 잘못
들어간 것이고, 둘 다 `EDITOR` 로 나오면 같은 비밀번호를 두 번 넣은 것이다.

> 로그인 실패는 5분에 10회로 제한된다. 확인하다 막히면 5분 뒤에 다시 시도한다.

### 비밀번호 바꾸기

**두 개를 따로 바꿀 수 있다.** 조회용만 교체하는 경우 편집용 사용자는 로그인을 유지한다.

1. 새 해시를 만든다 (위 명령)
2. 서버의 `deploy/.env` 에서 해당 줄만 교체한다
3. `docker compose up -d backend` — 컨테이너가 새 환경변수로 다시 뜬다
4. 로그로 기동을 확인하고, 새 비밀번호로 로그인이 되는지 확인한다

이미 발급된 세션은 `AUTH_TOKEN_SECRET` 을 바꾸지 않는 한 만료 시각(기본 12시간)까지
그대로 유효하다. **유출이 의심되어 즉시 전원을 로그아웃시켜야 한다면 비밀번호와 함께
`AUTH_TOKEN_SECRET` 도 교체한다.**

### 주의

- **두 비밀번호를 같은 평문으로 설정하는 실수는 앱이 잡지 못한다.** BCrypt는 매번 다른
  솔트를 쓰므로 같은 비밀번호라도 해시 문자열이 달라지고, 서버에 평문이 없어 비교할
  방법이 없다. 잡히는 것은 같은 해시를 복붙한 경우까지다. 설정하는 사람이 확인해야 한다.
- 평문 비밀번호와 해시는 저장소에 커밋하지 않는다. `.env` 는 gitignore 대상이다.
- 비밀번호를 공유할 때는 이 문서가 아니라 별도 경로(비밀번호 관리자 등)를 쓴다.

## 구글 시트 자동 동기화 (KAN-88, KAN-89)

회의록 시트를 매시 읽어 카드/경비 일정에 반영한다. **기본은 꺼짐**이고, 아래 셋을
`.env` 에 채운 뒤 백엔드를 다시 올려야 돈다.

```
SHEETS_SYNC_ENABLED=true
SHEETS_SPREADSHEET_ID=<문서 URL 의 /d/ 와 /edit 사이 문자열>
SHEETS_CREDENTIALS_JSON=<서비스 계정 키 JSON 을 base64 한 줄로>
```

키를 base64 로 넣는 이유는 원본 JSON 이 줄이 여러 개이고 개인키 안에 `$` 와 따옴표가
섞여 있어서다. 그대로 넣으면 `.env` 를 지나는 동안 잘린다.

```bash
base64 -w0 lab-calendar-sheets.json   # 이 한 줄을 값으로 붙여 넣는다
```

셋 중 하나라도 비면 **앱이 기동을 거부한다** — 켜 놓고 반쯤 설정된 채로 매시 실패하는
것보다 낫다.

### 켠 뒤 확인

```bash
cd ~/lab-calendar/deploy
docker compose up -d backend
docker compose logs --tail=50 backend | grep -i 시트
```

`시트 동기화 완료 — 추가 N건, 삭제 N건` 이 보이면 정상이다. 실패는 이유와 함께 남고,
같은 내용이 화면의 **카드 내역 → 가져오기 이력**에도 보인다.

### 안전장치

아무도 보지 않는 사이에 한 달치가 지워지는 일을 막는다.

- 한 달에서 **20건을 넘게, 그리고 그 달의 50%를 넘게** 지울 것 같으면 그 달을 건너뛰고
  이유를 남긴다 (`REMOVAL_LIMIT`). 둘 다 넘어야 걸린다
- 읽을 월 탭이 하나도 없으면 "전부 삭제"가 아니라 아무것도 하지 않는다
- 권한 상실·시트 삭제·속도 제한은 각각 다른 코드로 이력에 남는다
- 앞 회차나 엑셀 업로드가 돌고 있으면 이번 회차는 건너뛴다

기준과 주기는 `.env` 에서 바꾼다 — `SHEETS_MAX_REMOVALS_PER_MONTH`,
`SHEETS_MAX_REMOVAL_RATIO`, `SHEETS_SYNC_CRON`. 비워 두면 매시 정각 · 20건 · 50% 다.

### 공유가 끊겼을 때

이력에 `SHEETS_PERMISSION_DENIED` 가 보이면 시트 공유가 풀렸거나 Sheets API 사용
설정이 꺼진 것이다. 시트 소유자에게 서비스 계정 주소를 **뷰어**로 다시 넣어 달라고
요청한다. 주소는 키 JSON 의 `client_email` 값이다.

## 상태 확인과 로그 (KAN-68)

### 컨테이너가 살아 있는지

백엔드에 헬스체크가 붙어 있습니다. 프로세스만 떠 있는 것이 아니라 **DB 연결까지**
확인하므로, DB 가 끊기면 `unhealthy` 로 바뀝니다.

```bash
docker compose ps
# STATUS 가 "Up 2 hours (healthy)" 처럼 나옵니다
```

기동 직후 90초 동안은 실패를 세지 않습니다(`start_period`). 그 사이의 `starting`
은 정상입니다.

직접 물어볼 수도 있습니다. 바깥에서는 닿지 않고 서버 안에서만 됩니다 — nginx 는
`/api` 만 넘깁니다.

```bash
docker compose exec backend wget -qO- http://localhost:8080/actuator/health
# {"status":"UP"}
```

`DOWN` 이면 원인은 응답이 아니라 로그에 있습니다.

### 로그 보기

```bash
docker compose logs -f backend          # 따라가며 보기
docker compose logs --tail=200 backend  # 최근 200줄
```

로그는 컨테이너당 10MB × 5개로 잘립니다. 그 이상은 오래된 것부터 버려지므로
디스크가 로그로 차지 않습니다. 오래 보관해야 할 것이 있으면 따로 내보내세요.

### SQL 로그

운영에서는 나오지 않습니다. 바인딩 값에 참석자 이름이 섞여 나가기 때문입니다.
개발 중에 보려면 `application-local.yml` 에서 켜세요 (`.example` 파일에 주석으로
적어 두었습니다).
