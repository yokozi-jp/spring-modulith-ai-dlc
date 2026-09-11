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

## 運用

- 番号は連番で、廃止しても再利用しない。
- ファイル名は `ADR-NNN-<kebab-case-title>.md`。
- 状態は Proposed / Accepted / Deprecated / Superseded で管理し、
  置き換えは `Superseded by ADR-NNN` で相互リンクする。
- 新しい決定は実装前に Proposed として起こし、PR でレビューする。
