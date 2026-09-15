# Architecture Decision Records

このディレクトリは、ワークフローの外で既に確定している重要な設計判断を記録する。
書式は AI-DLC 同梱の
[`adr-template.md`](../../.kiro/knowledge/aidlc-architect-agent/adr-template.md)
に揃える（`ADR-NNN`、Status / Date / Context / Decision / Consequences /
Alternatives Considered / References の構成）。

インテント単位の設計判断は AI-DLC が inception 実行時に
`<record>/inception/domain-design/decisions.md` へ生成する。
このディレクトリは、それより前・外で下された横断的な決定を残す場所である。

以下の ADR は、実装済みの決定を遡って記録した（backfill）。

## 一覧

- **ADR-001**：Spring Modulith によるモジュラーモノリス（Accepted, 2026-09-11）
- **ADR-002**：package by feature とオニオンアーキテクチャ（Accepted, 2026-09-11）
- **ADR-003**：データアクセスに jOOQ を採用（Accepted, 2026-09-11）
- **ADR-004**：jOOQ 生成コードを Git 管理する（Accepted, 2026-09-11）
- **ADR-005**：Liquibase をアプリケーション起動から分離する（Accepted, 2026-09-11）
- **ADR-006**：絶対時刻を UTC / Instant / timestamptz に統一する（Accepted, 2026-09-11）
- **ADR-007**：セッションベース認証と OIDC Authorization Code + PKCE（Accepted, 2026-09-11）
- **ADR-008**：application.yaml を単一にし設定を外部注入する（Accepted, 2026-09-11）
- **ADR-009**：DB 接続を単一アカウントから二つの役割へ分ける（Superseded by ADR-011, 2026-09-13）
- **ADR-010**：プロジェクトのタスクランナーにTaskを採用する（Proposed, 2026-09-13）
- **ADR-011**：モジュール所有のデータベーススキーマを使う（Proposed, 2026-09-14）
- **ADR-012**：プロパティベーステストとミューテーションテストを採用する（Proposed, 2026-09-14）
- **ADR-013**：HTTP API 契約を標準化する（Accepted, 2026-09-14）
- **ADR-014**：SPA とバックエンドを同一オリジンで公開する（Accepted, 2026-09-14）
- **ADR-015**：可観測性データを構造化し保護する（Proposed, 2026-09-15）
- **ADR-016**：API と SPA のメッセージをローカライズする（Proposed, 2026-09-15）
- **ADR-017**：トランクベース開発とリポジトリ保護を採用する（Proposed, 2026-09-15）
- **ADR-018**：release-please でセマンティックリリースを自動化する（Proposed, 2026-09-15）
- **ADR-019**：外部連携の耐障害性と容量制御を標準化する（Proposed, 2026-09-15）
- **ADR-020**：コンテナイメージを署名し provenance を検証する（Proposed, 2026-09-15）

## 運用

- 番号は連番で、廃止しても再利用しない。
- ファイル名は `ADR-NNN-<kebab-case-title>.md`。
- 状態は Proposed / Accepted / Deprecated / Superseded で管理し、
  置き換えは `Superseded by ADR-NNN` で相互リンクする。
- 新しい決定は実装前に Proposed として起こし、PR でレビューする。
