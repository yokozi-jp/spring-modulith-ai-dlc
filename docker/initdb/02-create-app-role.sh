#!/bin/sh
# 初回起動時に、DML 限定のアプリケーションロールを作成する（データボリュームが空のときだけ）。
# ブートストラップの POSTGRES_USER は DDL とスキーマ所有を持つマイグレーション用ロール。
# アプリロールはスキーマの USAGE と、既存・将来テーブルへの DML（SELECT/INSERT/UPDATE/DELETE）
# とシーケンス使用だけを持ち、CREATE/ALTER/DROP を持たない（最小権限。ADR-009）。
#
# 受け渡す資格情報は、プロジェクトのアプリ用 DB_USERNAME/DB_PASSWORD をそのまま使う。
# ブートストラップに POSTGRES_USER を使う都合でアプリロールだけ別経路で渡すが、値も名前も
# DB_USERNAME/DB_PASSWORD で、専用の別名は設けない。
#   DB_USERNAME       アプリロール名
#   DB_PASSWORD_FILE  アプリロールのパスワードを収めた secret ファイル（compose.yml）
#   DB_PASSWORD       アプリロールのパスワード（compose-test.yml の平文）
#   DB_SCHEMA         対象スキーマ（既定 demo。01 で作成済み）
#
# アプリロール未指定、またはマイグレーションロールと同名なら単一ロール運用とみなし、何もしない。
# postgres の初期化エントリポイントは非実行ファイルの .sh を source するため、
# トップレベルで exit せず if/else で分岐する（実行ビットは付与済み）。

app_user="${DB_USERNAME:-}"
schema="${DB_SCHEMA:-demo}"

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

    # 識別子とリテラルは psql 変数（:"ident" と :'literal'）で安全に引用し、値の混入を防ぐ。
    # ALTER DEFAULT PRIVILEGES は FOR ROLE を省略し、この接続ロール（マイグレーションロール）が
    # 今後作成するテーブル・シーケンスへ自動で DML を付与する。Liquibase は同じロールで接続する。
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        -v app_user="$app_user" -v app_password="$app_password" -v schema="$schema" <<'EOSQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';

GRANT USAGE ON SCHEMA :"schema" TO :"app_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA :"schema" TO :"app_user";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA :"schema" TO :"app_user";

ALTER DEFAULT PRIVILEGES IN SCHEMA :"schema"
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
ALTER DEFAULT PRIVILEGES IN SCHEMA :"schema"
    GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
EOSQL

    echo "02-create-app-role: DML 限定のアプリロール \"$app_user\" を作成し、スキーマ \"$schema\" へ権限を付与しました。"
fi
