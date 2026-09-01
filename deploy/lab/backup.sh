#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=${JOBVIS_ENV_FILE:-"$script_dir/.env"}
compose_file="$script_dir/compose.yaml"
backup_dir="$script_dir/backups"

[ -f "$env_file" ] || {
  echo "오류: $env_file 이 없습니다. 먼저 prepare-env.sh를 실행하세요." >&2
  exit 1
}
command -v docker >/dev/null 2>&1 || {
  echo "오류: docker가 필요합니다." >&2
  exit 1
}

umask 077
mkdir -p "$backup_dir"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_file="$backup_dir/jobvis-$timestamp.dump"
partial_file="$backup_file.partial"
trap 'rm -f "$partial_file"' EXIT HUP INT TERM

docker compose --env-file "$env_file" -f "$compose_file" exec -T postgres \
  sh -eu -c 'pg_dump --format=custom --compress=9 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
  >"$partial_file"

[ -s "$partial_file" ] || {
  echo "오류: 비어 있는 백업이 생성되었습니다." >&2
  exit 1
}
mv "$partial_file" "$backup_file"
trap - EXIT HUP INT TERM

backup_name=$(basename -- "$backup_file")
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_dir" && sha256sum "$backup_name" >"$backup_name.sha256")
else
  (cd "$backup_dir" && shasum -a 256 "$backup_name" >"$backup_name.sha256")
fi

find "$backup_dir" -type f \( -name 'jobvis-*.dump' -o -name 'jobvis-*.dump.sha256' \) -mtime +30 -delete
echo "백업 완료: $backup_file"
echo "같은 VM 장애에 대비해 dump, checksum, .env를 VM 밖의 안전한 위치로 복사하세요."
