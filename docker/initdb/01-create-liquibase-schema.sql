-- Liquibase は changeset 実行前に管理テーブルを作るため、管理専用スキーマだけを事前作成する。
-- modulith と業務モジュールのスキーマは Liquibase changeset が作成する。
CREATE SCHEMA liquibase AUTHORIZATION CURRENT_USER;

-- 新しい接続がコンテナのOS設定に依存しないよう、現在のロールとDBの組み合わせをUTCに固定する。
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
