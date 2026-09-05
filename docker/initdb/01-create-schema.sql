-- ローカル開発用 PostgreSQL の初回起動時にだけ実行される（データボリュームが空のとき）。
-- アプリのテーブルと Liquibase 管理テーブル（DATABASECHANGELOG 等）を置くスキーマ demo を用意する。
-- Hikari の schema と Liquibase の default-schema が demo を指すため、起動前にスキーマが存在する必要がある。
-- 既存ボリュームには再実行されないので、public から demo へ切り替えるときは make compose-reset で作り直す。
CREATE SCHEMA IF NOT EXISTS demo AUTHORIZATION demo;
