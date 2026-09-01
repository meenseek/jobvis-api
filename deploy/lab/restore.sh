#!/bin/sh
set -eu

usage() {
  echo "사용법: $0 <jobvis-*.dump> RESTORE" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
backup_file=$1
confirmation=$2
[ "$confirmation" = "RESTORE" ] || usage
[ -f "$backup_file" ] || {
  echo "오류: 백업 파일을 찾을 수 없습니다: $backup_file" >&2
  exit 1
}
[ -s "$backup_file" ] || {
  echo "오류: 백업 파일이 비어 있습니다: $backup_file" >&2
  exit 1
}
[ -f "$backup_file.sha256" ] || {
  echo "오류: 필수 checksum 파일이 없습니다: $backup_file.sha256" >&2
  exit 1
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
env_file=${JOBVIS_ENV_FILE:-"$script_dir/.env"}
compose_file="$script_dir/compose.yaml"
[ -f "$env_file" ] || {
  echo "오류: $env_file 이 없습니다." >&2
  exit 1
}

run_compose() {
  docker compose --env-file "$env_file" -f "$compose_file" "$@"
}

backup_dir=$(CDPATH= cd -- "$(dirname -- "$backup_file")" && pwd)
backup_name=$(basename -- "$backup_file")
database_name=$(sed -n 's/^JOBVIS_POSTGRES_DB=//p' "$env_file" | tail -n 1)
[ -n "$database_name" ] || {
  echo "오류: JOBVIS_POSTGRES_DB를 찾을 수 없습니다." >&2
  exit 1
}
case "$database_name" in
  postgres|template0|template1)
    echo "오류: PostgreSQL 유지보수 database는 복구 대상으로 사용할 수 없습니다." >&2
    exit 1
    ;;
  ""|[0-9]*|*[!A-Za-z0-9_]*)
    echo "오류: JOBVIS_POSTGRES_DB는 영문자/밑줄로 시작하는 영문자, 숫자, 밑줄 조합이어야 합니다." >&2
    exit 1
    ;;
esac

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_dir" && sha256sum --check "$backup_name.sha256")
else
  (cd "$backup_dir" && shasum -a 256 --check "$backup_name.sha256")
fi

echo "현재 Jobvis 데이터베이스를 $backup_file 내용으로 교체합니다."
printf "계속하려면 데이터베이스 이름 %s 을 입력하세요: " "$database_name"
read -r typed_name
[ "$typed_name" = "$database_name" ] || {
  echo "복구를 취소했습니다." >&2
  exit 1
}

api_was_running=false
if run_compose ps --status running --services | grep -qx api; then
  api_was_running=true
fi
caddy_was_running=false
if run_compose ps --status running --services | grep -qx caddy; then
  caddy_was_running=true
fi
temp_created=false
swap_started=false
swapped=false
candidate_adopted=false
restore_finished=false
restore_suffix=$(date -u +%Y%m%d%H%M%S)_$$
temp_database=jobvis_restore_$restore_suffix
previous_database=jobvis_previous_$restore_suffix

recover_original_database() {
  run_compose exec -T \
    --env RESTORE_CURRENT_DATABASE="$database_name" \
    --env RESTORE_TEMP_DATABASE="$temp_database" \
    --env RESTORE_PREVIOUS_DATABASE="$previous_database" \
    postgres sh -eu -c '
      database_exists() {
        candidate_database=$1
        psql --username="$POSTGRES_USER" --dbname=postgres --tuples-only --no-align \
          --command="SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = '\''$candidate_database'\'')"
      }

      disable_database() {
        target_database=$1
        psql --username="$POSTGRES_USER" --dbname=postgres --set=ON_ERROR_STOP=1 \
          --command="ALTER DATABASE \"$target_database\" WITH ALLOW_CONNECTIONS false; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '\''$target_database'\'' AND pid <> pg_backend_pid();"
      }

      enable_database() {
        target_database=$1
        psql --username="$POSTGRES_USER" --dbname=postgres --set=ON_ERROR_STOP=1 \
          --command="ALTER DATABASE \"$target_database\" WITH ALLOW_CONNECTIONS true"
      }

      rename_database() {
        source_database=$1
        target_database=$2
        psql --username="$POSTGRES_USER" --dbname=postgres --set=ON_ERROR_STOP=1 \
          --command="ALTER DATABASE \"$source_database\" RENAME TO \"$target_database\""
      }

      drop_database() {
        target_database=$1
        dropdb --if-exists --force --username="$POSTGRES_USER" "$target_database"
      }

      current_exists=$(database_exists "$RESTORE_CURRENT_DATABASE")
      previous_exists=$(database_exists "$RESTORE_PREVIOUS_DATABASE")
      temp_exists=$(database_exists "$RESTORE_TEMP_DATABASE")

      if [ "$previous_exists" = t ]; then
        if [ "$current_exists" = t ]; then
          [ "$temp_exists" = f ] || {
            echo "오류: current/previous/temp database가 모두 존재하여 자동 원복을 중단합니다." >&2
            exit 1
          }
          disable_database "$RESTORE_CURRENT_DATABASE"
          disable_database "$RESTORE_PREVIOUS_DATABASE"
          rename_database "$RESTORE_CURRENT_DATABASE" "$RESTORE_TEMP_DATABASE"
          temp_exists=t
        else
          disable_database "$RESTORE_PREVIOUS_DATABASE"
          if [ "$temp_exists" = t ]; then
            disable_database "$RESTORE_TEMP_DATABASE"
          fi
        fi

        rename_database "$RESTORE_PREVIOUS_DATABASE" "$RESTORE_CURRENT_DATABASE"
        enable_database "$RESTORE_CURRENT_DATABASE"
        if [ "$temp_exists" = t ]; then
          drop_database "$RESTORE_TEMP_DATABASE"
        fi
      else
        [ "$current_exists" = t ] || {
          echo "오류: 원본 database 이름을 자동 복구할 수 없습니다." >&2
          exit 1
        }
        enable_database "$RESTORE_CURRENT_DATABASE"
        if [ "$temp_exists" = t ]; then
          drop_database "$RESTORE_TEMP_DATABASE"
        fi
      fi

      [ "$(database_exists "$RESTORE_CURRENT_DATABASE")" = t ]
      [ "$(database_exists "$RESTORE_PREVIOUS_DATABASE")" = f ]
      [ "$(database_exists "$RESTORE_TEMP_DATABASE")" = f ]
    '
}

cleanup_on_exit() {
  exit_code=$?
  trap - 0 HUP INT TERM
  recovery_succeeded=true
  if [ "$restore_finished" = false ] && [ "$candidate_adopted" = false ] && [ "$swap_started" = true ]; then
    if recover_original_database; then
      swap_started=false
      swapped=false
      temp_created=false
    else
      recovery_succeeded=false
      exit_code=1
      echo "치명적 오류: database 이름 자동 원복에 실패했습니다. API와 Caddy를 중지 상태로 유지합니다." >&2
    fi
  fi
  if [ "$restore_finished" = false ] && [ "$candidate_adopted" = false ] && [ "$swap_started" = false ] && [ "$temp_created" = true ]; then
    run_compose exec -T --env RESTORE_TEMP_DATABASE="$temp_database" postgres \
      sh -eu -c 'dropdb --if-exists --force --username="$POSTGRES_USER" "$RESTORE_TEMP_DATABASE"' \
      >/dev/null 2>&1 || true
  fi
  if [ "$restore_finished" = false ] && [ "$swapped" = false ] && [ "$recovery_succeeded" = true ]; then
    if [ "$api_was_running" = true ]; then
      run_compose up -d api >/dev/null 2>&1 || true
    fi
    if [ "$caddy_was_running" = true ]; then
      run_compose up -d --no-deps caddy >/dev/null 2>&1 || true
    fi
  fi
  exit "$exit_code"
}
trap cleanup_on_exit 0 HUP INT TERM

run_compose stop caddy
run_compose stop api
run_compose up -d postgres

attempt=0
until run_compose exec -T postgres \
  sh -eu -c 'pg_isready --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] || {
    echo "오류: PostgreSQL이 준비되지 않았습니다." >&2
    exit 1
  }
  sleep 2
done

run_compose exec -T --env RESTORE_TEMP_DATABASE="$temp_database" postgres \
  sh -eu -c 'createdb --username="$POSTGRES_USER" --owner="$POSTGRES_USER" "$RESTORE_TEMP_DATABASE"'
temp_created=true

run_compose exec -T --env RESTORE_TEMP_DATABASE="$temp_database" postgres \
  sh -eu -c 'pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$RESTORE_TEMP_DATABASE"' \
  <"$backup_file"

restored_contract=$(run_compose exec -T --env RESTORE_TEMP_DATABASE="$temp_database" postgres \
  sh -eu -c 'psql --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$RESTORE_TEMP_DATABASE" --command="SELECT to_regclass('"'"'public.flyway_schema_history'"'"') IS NOT NULL AND to_regclass('"'"'public.users'"'"') IS NOT NULL"')
[ "$restored_contract" = "t" ] || {
  echo "오류: dump가 Jobvis database 계약을 포함하지 않습니다." >&2
  exit 1
}

swap_started=true
run_compose exec -T \
  --env RESTORE_TEMP_DATABASE="$temp_database" \
  --env RESTORE_PREVIOUS_DATABASE="$previous_database" \
  postgres sh -eu -c '
    psql --username="$POSTGRES_USER" --dbname=postgres --set=ON_ERROR_STOP=1 \
      --set=restore_db="$POSTGRES_DB" \
      --set=temp_db="$RESTORE_TEMP_DATABASE" \
      --set=previous_db="$RESTORE_PREVIOUS_DATABASE"
  ' <<'SQL'
ALTER DATABASE :"restore_db" WITH ALLOW_CONNECTIONS false;
ALTER DATABASE :"temp_db" WITH ALLOW_CONNECTIONS false;
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname IN (:'restore_db', :'temp_db') AND pid <> pg_backend_pid();
ALTER DATABASE :"restore_db" RENAME TO :"previous_db";
ALTER DATABASE :"temp_db" RENAME TO :"restore_db";
ALTER DATABASE :"restore_db" WITH ALLOW_CONNECTIONS true;
SQL
swapped=true

api_healthy=false
if run_compose up -d api; then
  attempt=0
  while [ "$attempt" -lt 45 ]; do
    api_container=$(run_compose ps --quiet api)
    if [ -n "$api_container" ]; then
      api_health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$api_container" 2>/dev/null || true)
      if [ "$api_health" = "healthy" ]; then
        api_healthy=true
        break
      fi
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
fi

if [ "$api_healthy" = false ]; then
  echo "오류: 복원된 DB에서 API가 healthy가 아니므로 이전 DB로 되돌립니다." >&2
  run_compose stop api >/dev/null 2>&1 || true
  recover_original_database
  swap_started=false
  swapped=false
  temp_created=false
  exit 1
fi

candidate_adopted=true
temp_created=false
swap_started=false
swapped=false

if ! run_compose exec -T --env RESTORE_PREVIOUS_DATABASE="$previous_database" postgres \
  sh -eu -c 'dropdb --force --username="$POSTGRES_USER" "$RESTORE_PREVIOUS_DATABASE"'; then
  echo "오류: 새 DB는 이미 채택되어 유지합니다. 이전 DB $previous_database 만 후속 정리하세요." >&2
  exit 1
fi

if [ "$caddy_was_running" = true ]; then
  run_compose up -d --no-deps caddy
fi

restore_finished=true
echo "복구 완료. 새 API가 healthy이며 이전 DB를 정리했습니다. 공개 readiness를 확인하세요."
