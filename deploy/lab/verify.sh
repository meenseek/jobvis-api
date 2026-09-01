#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_file="$script_dir/compose.yaml"
example_env="$script_dir/.env.example"
env_file=${JOBVIS_ENV_FILE:-"$script_dir/.env"}

if [ ! -f "$env_file" ]; then
  env_file=$example_env
  checking_example=true
else
  checking_example=false
fi

command -v docker >/dev/null 2>&1 || {
  echo "오류: docker가 필요합니다." >&2
  exit 1
}

docker compose --env-file "$env_file" -f "$compose_file" config --quiet

published_ports=$(docker compose --env-file "$env_file" -f "$compose_file" config \
  | awk '/published:/ {gsub(/\"/, "", $2); print $2}' \
  | sort -u \
  | tr '\n' ' ')
[ "$published_ports" = "443 80 " ] || {
  echo "오류: 외부 공개 포트가 80/443으로 제한되지 않았습니다: $published_ports" >&2
  exit 1
}

if [ "$checking_example" = false ]; then
  postgres_password=$(sed -n 's/^JOBVIS_POSTGRES_PASSWORD=//p' "$env_file" | tail -n 1)
  encryption_key=$(sed -n 's/^JOBVIS_ENCRYPTION_KEY_BASE64=//p' "$env_file" | tail -n 1)
  trusted_site_secret=$(sed -n 's/^JOBVIS_TRUSTED_SITE_SECRET=//p' "$env_file" | tail -n 1)

  [ "${#postgres_password}" -ge 32 ] || {
    echo "오류: PostgreSQL 비밀번호가 32자보다 짧습니다." >&2
    exit 1
  }
  decoded_key_bytes=$(printf %s "$encryption_key" | openssl base64 -d -A | wc -c | tr -d ' ')
  [ "$decoded_key_bytes" = 32 ] || {
    echo "오류: 암호화 키는 Base64로 인코딩한 정확히 32바이트여야 합니다." >&2
    exit 1
  }
  [ "${#trusted_site_secret}" -ge 32 ] || {
    echo "오류: Sites 공유 비밀이 32자보다 짧습니다." >&2
    exit 1
  }
  case "$postgres_password:$trusted_site_secret" in
    *replace-with-*)
      echo "오류: 예시 비밀을 운영 .env에 사용할 수 없습니다." >&2
      exit 1
      ;;
  esac
  echo "운영 환경변수와 Compose 구성이 유효하며 외부 공개 포트는 80/443뿐입니다."
else
  echo "예시 환경변수 기준 Compose 구성이 유효하며 외부 공개 포트는 80/443뿐입니다."
fi
