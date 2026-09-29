#!/usr/bin/env bash
# Prompt for the shared editor/viewer passwords and store only their BCrypt
# hashes in deploy/.env. Plaintext is never echoed, written to disk, or passed
# as a command-line argument (htpasswd reads it from stdin).
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo ".env not found — copy .env.example first"; exit 1; }

ask() {
  local label="$1" first second
  while :; do
    read -rsp "$label 비밀번호: " first; echo >&2
    read -rsp "$label 비밀번호 확인: " second; echo >&2
    if [ -z "$first" ]; then echo "비어 있으면 안 됩니다." >&2
    elif [ "$first" != "$second" ]; then echo "두 번 입력한 값이 다릅니다." >&2
    else printf '%s' "$first"; return; fi
  done
}

hash() {
  printf '%s' "$1" | docker run --rm -i httpd:alpine htpasswd -niBC 10 "" | tr -d ':\n'
}

editor="$(ask 편집용)"
viewer="$(ask 조회용)"
if [ "$editor" = "$viewer" ]; then
  echo "편집용과 조회용 비밀번호가 같습니다. 조회 등급이 편집 권한을 갖게 되므로 다르게 설정하세요."
  exit 1
fi

editor_hash="$(hash "$editor")"
viewer_hash="$(hash "$viewer")"
unset editor viewer

# Rewrite the two lines; single quotes stop Compose from expanding the $ in the hash.
tmp="$(mktemp)"
grep -v -e '^AUTH_EDITOR_PASSWORD_HASH=' -e '^AUTH_VIEWER_PASSWORD_HASH=' .env > "$tmp"
printf "AUTH_EDITOR_PASSWORD_HASH='%s'\nAUTH_VIEWER_PASSWORD_HASH='%s'\n" "$editor_hash" "$viewer_hash" >> "$tmp"
chmod 600 "$tmp"
mv "$tmp" .env

echo "저장했습니다. 반영하려면: docker compose up -d backend"
