#!/bin/sh
# 初回起動時に、表を所有してDDLを流すマイグレーションロールを作る。
# POSTGRES_USER の superuser はこの初期化だけに使い、マイグレーションとアプリは使わない。
# マイグレーションロールを superuser にも BYPASSRLS にもしないのは、
# FORCE ROW LEVEL SECURITY を付けた表で、本番と同じく所有者にも RLS を効かせるためである。
set -e

migration_user="${MIGRATION_DB_USERNAME:-}"
if [ -n "${MIGRATION_DB_PASSWORD_FILE:-}" ]; then
    migration_password="$(cat "$MIGRATION_DB_PASSWORD_FILE")"
else
    migration_password="${MIGRATION_DB_PASSWORD:-}"
fi

if [ -z "$migration_user" ] || [ -z "$migration_password" ]; then
    echo "01-create-migration-role: MIGRATION_DB_USERNAME と、MIGRATION_DB_PASSWORD または MIGRATION_DB_PASSWORD_FILE が必要です。" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v migration_user="$migration_user" -v migration_password="$migration_password" \
    -v db_name="$POSTGRES_DB" <<'EOSQL'
CREATE ROLE :"migration_user" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD :'migration_password';
-- changeset がモジュールのスキーマを作るため、DB には CREATE だけを与える。
GRANT CREATE ON DATABASE :"db_name" TO :"migration_user";
-- Liquibase は changeset より先に管理表を作るため、管理用のスキーマだけを先に作る。
CREATE SCHEMA liquibase AUTHORIZATION :"migration_user";
-- 新しい接続がコンテナの OS の設定に依存しないよう、マイグレーションロールと DB の組み合わせを UTC に固定する。
ALTER ROLE :"migration_user" IN DATABASE :"db_name" SET timezone TO 'UTC';
EOSQL

echo "01-create-migration-role: superuser でも BYPASSRLS でもないマイグレーションロール \"$migration_user\" を作成しました。"
