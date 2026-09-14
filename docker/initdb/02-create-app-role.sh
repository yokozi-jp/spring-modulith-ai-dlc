#!/bin/sh
# 初回起動時に、DDLを持たないアプリケーションロールを作成する。
# スキーマとテーブルの権限は、各Liquibase changesetが所有対象だけに付与する。

app_user="${DB_USERNAME:-}"

if [ -z "$app_user" ] || [ "$app_user" = "$POSTGRES_USER" ]; then
    echo "02-create-app-role: DB_USERNAME 未設定または POSTGRES_USER と同一のため、アプリロール作成をスキップします。"
else
    if [ -n "${DB_PASSWORD_FILE:-}" ]; then
        app_password="$(cat "$DB_PASSWORD_FILE")"
    else
        app_password="${DB_PASSWORD:-}"
    fi

    if [ -z "$app_password" ]; then
        echo "02-create-app-role: DB_PASSWORD または DB_PASSWORD_FILE が必要です。" >&2
        exit 1
    fi

    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        -v app_user="$app_user" -v app_password="$app_password" <<'EOSQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
EOSQL

    echo "02-create-app-role: DDL権限を持たないアプリロール \"$app_user\" を作成しました。"
fi
