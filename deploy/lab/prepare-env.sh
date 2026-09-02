#!/bin/sh
set -eu

usage() {
  echo "사용법: $0 <API hostname> <Web HTTPS origin> <TLS 알림 이메일>" >&2
  exit 2
}

fail() {
  echo "오류: $1" >&2
  exit 1
}

[ "$#" -eq 3 ] || usage

api_host=$1
web_origin=$2
tls_email=$3
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file="$script_dir/.env"

case "$api_host" in
  ""|*://*|*/*|*:*|*\?*|*\#*|.*|*.) fail "API hostname에는 scheme, port, path를 넣지 마세요." ;;
  *[!A-Za-z0-9.-]*) fail "API hostname에는 영문, 숫자, 점, 하이픈만 사용할 수 있습니다." ;;
  *.*) ;;
  *) fail "공인 HTTPS 발급에 사용할 전체 hostname을 입력하세요." ;;
esac

case "$web_origin" in
  https://*) ;;
  *) fail "Web origin은 https://로 시작해야 합니다." ;;
esac
web_authority=${web_origin#https://}
case "$web_authority" in
  ""|*/*|*\?*|*\#*) fail "Web origin에는 path, query, fragment를 넣지 마세요." ;;
  *[!A-Za-z0-9.:-]*) fail "Web origin의 hostname 또는 port 형식을 확인하세요." ;;
esac

case "$tls_email" in
  *@*.*) ;;
  *) fail "TLS 알림 이메일 형식을 확인하세요." ;;
esac

[ ! -e "$env_file" ] || fail "$env_file 이 이미 있습니다. 기존 비밀을 보존하기 위해 덮어쓰지 않았습니다."
command -v openssl >/dev/null 2>&1 || fail "openssl이 필요합니다."

umask 077
postgres_password=$(openssl rand -hex 32)
encryption_key=$(openssl rand -base64 32)
temp_file="$env_file.tmp.$$"
trap 'rm -f "$temp_file"' EXIT HUP INT TERM

cat >"$temp_file" <<EOF
JOBVIS_API_HOST=$api_host
JOBVIS_TLS_EMAIL=$tls_email
JOBVIS_WEB_ORIGIN=$web_origin
JOBVIS_POSTGRES_DB=jobvis
JOBVIS_POSTGRES_USER=jobvis
JOBVIS_POSTGRES_PASSWORD=$postgres_password
JOBVIS_ENCRYPTION_KEY_BASE64=$encryption_key
JOBVIS_API_MEMORY_LIMIT=1024m
JOBVIS_POSTGRES_MEMORY_LIMIT=768m
JOBVIS_CADDY_MEMORY_LIMIT=192m
JOBVIS_GOOGLE_CLIENT_ID=
JOBVIS_GMAIL_CLIENT_ID=
JOBVIS_GMAIL_CLIENT_SECRET=
JOBVIS_MICROSOFT_CLIENT_ID=
JOBVIS_MICROSOFT_CLIENT_SECRET=
JOBVIS_GOOGLE_CALENDAR_CLIENT_ID=
JOBVIS_GOOGLE_CALENDAR_CLIENT_SECRET=
EOF

chmod 600 "$temp_file"
mv "$temp_file" "$env_file"
trap - EXIT HUP INT TERM

echo "$env_file 을 생성했습니다. 비밀 값은 출력하지 않았습니다."
echo "이 파일과 데이터베이스 백업을 서로 다른 안전한 위치에 함께 보관하세요."
