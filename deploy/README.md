# deploy/

EC2 위에서 backend + nginx(프론트) + certbot을 Docker Compose로 띄우기 위한 배포 매니페스트.

## 최초 셋업 (EC2에서)

```bash
cp .env.example .env   # DB_*, CERTBOT_EMAIL, AUTH_* 채우기 (아래 "공용 비밀번호" 참고)
docker compose up -d backend
CERTBOT_EMAIL=$(grep CERTBOT_EMAIL .env | cut -d= -f2) ./init-letsencrypt.sh
docker compose up -d
```

이후 GitHub Actions가 각 레포의 `main` push마다 새 이미지를 pull 받아
`docker compose up -d backend` / `docker compose up -d nginx`로 갱신한다
(각 레포의 `.github/workflows/deploy.yml` 참고).

## 파일 구성

- `docker-compose.yml` — backend, nginx(프론트 이미지), certbot 3개 서비스
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

EC2에 Docker가 있으므로 별도 설치 없이 만들 수 있다.

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
2. EC2의 `deploy/.env` 에서 해당 줄만 교체한다
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
