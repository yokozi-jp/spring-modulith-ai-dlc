-- ローカル開発・テスト用 PostgreSQL の初回起動時にだけ実行される（データボリュームが空のとき）。
-- ブートストラップの POSTGRES_USER は、DDL とスキーマ所有を持つマイグレーション用ロール。
-- アプリのテーブルと Liquibase 管理テーブル（DATABASECHANGELOG 等）を置くスキーマ demo を、
-- そのマイグレーションロールの所有で用意する（DML 限定のアプリロールは 02 で作成する）。
-- Hikari の schema と Liquibase の default-schema が demo を指すため、起動前にスキーマが存在する必要がある。
-- 既存ボリュームには再実行されないので、単一ロールから二ロールへ切り替えるときは task compose-reset で作り直す。
CREATE SCHEMA IF NOT EXISTS demo AUTHORIZATION CURRENT_USER;

-- 新しい接続がコンテナのOS設定に依存しないよう、現在のロールとDBの組み合わせをUTCに固定する。
-- DOブロックで識別子を安全に組み立て、POSTGRES_USERとPOSTGRES_DBの変更にも追従する。
DO $$
BEGIN
    EXECUTE format(
        'ALTER ROLE %I IN DATABASE %I SET timezone TO %L',
        current_user,
        current_database(),
        'UTC'
    );
END
$$;
