#!/bin/sh
set -eu

usage() {
  echo "사용법: $0 CUTOVER" >&2
  exit 2
}

[ "$#" -eq 1 ] && [ "$1" = "CUTOVER" ] || usage

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=${JOBVIS_ENV_FILE:-"$script_dir/.env"}
compose_file="$script_dir/compose.yaml"
[ -f "$env_file" ] || {
  echo "오류: $env_file 이 없습니다." >&2
  exit 1
}

api_host=$(sed -n 's/^JOBVIS_API_HOST=//p' "$env_file" | tail -n 1)
[ -n "$api_host" ] || {
  echo "오류: JOBVIS_API_HOST를 찾을 수 없습니다." >&2
  exit 1
}
[ -n "$(docker compose --env-file "$env_file" -f "$compose_file" ps --quiet api)" ] || {
  echo "오류: 중지할 기존 API 컨테이너가 없습니다." >&2
  exit 1
}

echo "이 명령 뒤에는 이전 VM이 쓰기를 받지 않습니다."
printf "계속하려면 이전 API hostname %s 을 입력하세요: " "$api_host"
read -r typed_host
[ "$typed_host" = "$api_host" ] || {
  echo "전환을 취소했습니다." >&2
  exit 1
}

docker compose --env-file "$env_file" -f "$compose_file" stop api
echo "이전 API를 중지했습니다. 최종 백업을 생성합니다."
JOBVIS_ENV_FILE="$env_file" "$script_dir/backup.sh"
echo "최종 백업 완료. 전환을 끝내거나 명시적으로 rollback하기 전에는 이전 API를 재시작하지 마세요."
