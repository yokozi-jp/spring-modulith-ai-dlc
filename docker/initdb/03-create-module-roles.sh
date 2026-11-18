#!/bin/sh
# 初回起動時に、モジュールごとのログインできないロール <module>_module を作り、アプリロールを継承なしのメンバーにする。
# アプリロールは業務のスキーマに権限を持たず、トランザクションの初めに SET ROLE 相当でモジュールのロールへ切り替えたときだけ、
# そのモジュールのスキーマを読み書きできる（ADR-094）。
# モジュールを足したら、ここと backend の ModuleRoles.MODULES に同じ名前を足す。
set -e

app_user="${DB_USERNAME:-}"
if [ -z "$app_user" ]; then
    echo "03-create-module-roles: DB_USERNAME が必要です。" >&2
    exit 1
fi

modules="product ordering payment"

for module in $modules; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
        -v module_role="${module}_module" -v app_user="$app_user" <<'EOSQL'
CREATE ROLE :"module_role" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
-- INHERIT FALSE で、アプリロールはモジュールのロールの権限を既定では使えない。SET TRUE で、切り替えだけを許す。
GRANT :"module_role" TO :"app_user" WITH INHERIT FALSE, SET TRUE;
EOSQL
done

echo "03-create-module-roles: モジュールのロール（$modules）を作成し、\"$app_user\" を継承なしのメンバーにしました。"
