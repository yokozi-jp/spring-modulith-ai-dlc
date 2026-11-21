-- ログインの後に選ぶテナント（e2e/environment.ts の chooseSeededTenant と同じ名前）の public_id を返す。
-- doctor と drive.mjs が、業務の表を読む前にこれでテナントを引く（ADR-107）。tenancy の表は RLS の外にある。
SET ROLE tenancy_module;
SELECT public_id FROM tenancy.m_tenant WHERE tenant_name = 'サンプル商事';
