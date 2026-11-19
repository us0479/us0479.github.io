#!/usr/bin/env bash
set -euo pipefail
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --set=app_password="$APP_DB_PASSWORD" <<'SQL'
CREATE ROLE bankwatch_app LOGIN PASSWORD :'app_password';
SQL
for sql_file in schema.sql seed.sql grant_runtime.sql; do
  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --file "/project/db/$sql_file"
done
