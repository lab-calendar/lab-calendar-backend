# deploy/

EC2 위에서 backend + nginx(프론트) + certbot을 Docker Compose로 띄우기 위한 배포 매니페스트.

## 최초 셋업 (EC2에서)

```bash
cp .env.example .env   # DB_URL, DB_USERNAME, DB_PASSWORD, CERTBOT_EMAIL 채우기
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
