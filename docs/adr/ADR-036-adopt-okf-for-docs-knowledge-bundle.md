---
type: Architecture Decision Record
title: 'ADR-036: docs/ を OKF v0.2 バンドルとして構成する'
description: ドキュメントの鮮度と出所を機械判定できるようにするため、docs/ を OKF v0.2 バンドルとして構成する決定。
tags: [adr, documentation, okf, knowledge-management]
---

# ADR-036: docs/ を OKF v0.2 バンドルとして構成する

## Status

Proposed

## Date

2026-09-30

## Context

`docs/` は markdown ドキュメント 45 件（ADR、アーキテクチャ解説、環境構築手順、運用手順）を持つ。 これらは AI エージェントが参照する知識だが、どのドキュメントが最新か、誰が生成したか、何を出所とするかを機械的に判定する手段がない。 現状 45 件はいずれもフロントマターを持たず、素の markdown である。

ナレッジ管理ツールとして iwe を導入済みで（ADR なし、開発環境構築手順に記載）、MCP サーバー `iwec` を `docs/` に向けて登録している。 iwe は Open Knowledge Format（OKF、Google Cloud が公開したエージェント向け知識のオープンフォーマット）v0.2 の足場を `iwe init --okf` で作れる。 OKF は YAML フロントマター付き markdown のディレクトリであり、出所（`sources`）、生成元（`generated`）、検証（`verified`）、ライフサイクル（`status`、`stale_after`）をフロントマターの一級項目として持つ。 専用ランタイムやレジストリを必要とせず、`iwe schema validate` で CI 検証できる。

足場は既に生成済みである。 `docs/.iwe/config.toml` に 3 つの適合スキーマ（概念ドキュメント用 `okf.yaml`、予約ファイル用 `okf-index.yaml` と `okf-log.yaml`）をバインドし、`refs_extension` を `.md` に設定し、bundle-root の `index.md`（`okf_version: "0.2"`）を作成した。 既存 45 件は一つも変更していない。

OKF 準拠（SPEC §11）は 3 規則からなる。 すべての非予約ファイルが解析可能なフロントマターを持つこと、すべてのフロントマターが空でない `type` を持つこと、`index.md` と `log.md` が規定の形状に従うことである。 現状は 45 件すべてが `type` を持たないため、いま `iwe schema validate` を走らせれば全件が違反となる。

## Decision

`docs/` を OKF v0.2 バンドルとして構成する。 `iwe init --okf` が生成した足場（`docs/.iwe/` の設定とスキーマ、bundle-root の `index.md`）を正式に採用し、コミットする。

既存 45 件のドキュメントには、内容に応じた `type` をフロントマターで付与する。 `type` の分類は、ADR、アーキテクチャ、手順、運用など、既存ドキュメントの実態に基づいて別途設計し、この ADR の承認後に確定する。

適合作業（フロントマター付与と `iwe normalize` による再整形）は、この足場採用とは別のステップで、コミット済みの状態から段階的に実施する。 `iwe schema validate` を CI に組み込み、以後の非適合を検出する。

`docs/` の外（`.kiro/`、`frontend/`、リポジトリルート）を指す相対リンク 28 件は、iwe の対象範囲外への参照であり実ファイルは存在する。 OKF 適合の対象外として扱い、この判断では解消しない。

## Consequences

### Positive

- AI エージェントが `status`、`verified`、`sources` でドキュメントを絞り込めるようになる（例：`iwe find --filter '{status: deprecated}'`）。
- ドキュメントの出所と鮮度がフロントマターに明示され、古い知識をエージェントが参照する事故を減らせる。
- `iwe schema validate` により、フロントマターだけでなく本文構造（index 形状、log 形状）まで CI で検証できる。
- OKF は専用ツールを要さないため、`git clone` できる相手なら iwe を使わなくても読める。

### Negative

- 既存 45 件すべてにフロントマターと `type` を付与する必要がある。
- `iwe normalize` は 44/45 件を再整形する見込みで、既存ドキュメントの差分が大きくなる。
- `type` の分類体系を設計し保守する負担が生じる。
- `.iwe/config.toml` には iwe の標準テンプレート（AI アクション定義や `run = "claude -p"` など）が含まれ、チーム共有すべき設定と個人環境の設定の切り分けが要る。

### Neutral

- 足場採用と適合作業を別ステップに分けるため、採用直後は「足場はあるが未適合」の状態が続く。
- OKF フィールドは通常のフロントマターなので、iwe 以外の markdown ツールからも読める。
- `docs/` 外への相対リンク 28 件は未解決のまま残す。別途の棚卸し対象とする。

## Alternatives Considered

### Alternative 1: OKF を採用しない

- Description：`docs/` を素の markdown のまま維持する。
- Pros：既存 45 件を変更せず、分類設計や normalize の影響を避けられる。
- Cons：ドキュメントの鮮度、出所、検証状態を機械的に判定できず、エージェントが古い知識を参照する事故を防げない。

### Alternative 2: フロントマターだけ独自に付与し、OKF スキーマにはしない

- Description：`type` や `status` を独自ルールで付けるが、OKF 適合スキーマと `iwe schema validate` は使わない。
- Pros：OKF 仕様への追従が不要になる。
- Cons：CI 検証の仕組みを自作する必要があり、既存の iwe と OKF エコシステム（参照バンドル、外部消費者）の互換性を失う。

### Alternative 3: 足場も作らず、iwe は検索と閲覧のみに使う

- Description：`iwe init --okf` を取り消し、iwe を find や retrieve のためだけに使う。
- Pros：`docs/` に一切の構造的変更を加えない。
- Cons：ドキュメントのメタデータを一切持てず、導入済みの iwe の価値を検索機能だけに限定してしまう。

## References

- [Open Knowledge Format](https://github.com/google/open-knowledge-format)
- [iwe](https://github.com/iwe-org/iwe)
- [開発環境構築](../local-env-setup/setup.md)
- `docs/.iwe/config.toml`
- `docs/.iwe/schemas/okf.yaml`
