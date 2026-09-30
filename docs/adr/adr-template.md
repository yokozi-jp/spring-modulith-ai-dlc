---
type: Reference
title: ADR テンプレート
description: このリポジトリの ADR が従う書式・採番・ライフサイクルのテンプレート。
tags: [adr, reference, template]
---

# ADR テンプレート

このリポジトリの Architecture Decision Record（ADR）は、以下の書式に従う。
運用ルールは [ADR の運用ルール](conventions.md) を参照する。

## ADR を作る基準

次のいずれかに当てはまる判断を ADR にする。

- 実装後に戻しにくい（DB、API 契約、フレームワーク、言語、認証方式など）。
- 複数のモジュールやチームに影響する。
- コスト、性能、セキュリティに大きく影響する。
- 議論や異論があった（合意形成の理由を残す価値がある）。
- 過去のアーキテクチャ方針を変更する。

次のものは ADR にしない。

- 変数名やコード整形などの日常的な実装選択。
- 自明に可逆な決定。
- すでにチーム規約や steering に文書化されている標準。

## 書式

見出し構成は Status / Date / Context / Decision / Consequences（Positive・Negative・Neutral）/ Alternatives Considered / References とする。
frontmatter に `type: Architecture Decision Record`、`title`、`description`、`tags` を置く。

```markdown
---
type: Architecture Decision Record
title: 'ADR-NNN: 短く内容を特定する題'
description: この判断を一文で要約する。
tags: [adr, ...]
---

# ADR-NNN: 短く内容を特定する題

## Status

Proposed | Accepted | Deprecated | Superseded by ADR-NNN

## Date

YYYY-MM-DD

## Context

この判断を必要とした制約と背景を書く。
解こうとしている問題、存在する制約（予算、期限、チームの習熟、コンプライアンス）、重視する品質特性（性能、セキュリティ、保守性）、既存の判断やシステムとの関係を具体的に書く。

## Decision

判断を能動態で言い切る。
「注文管理サービスの主データストアに PostgreSQL を使う」のように書き、「PostgreSQL が良いかもしれないと考えた」のように濁さない。

## Consequences

### Positive

- この判断によって何が容易、高速、改善されるか。緩和されるリスクは何か。

### Negative

- 何が難しく、あるいは高価になるか。新たに生じるリスク、閉ざされる選択肢は何か。

### Neutral

- 受け入れるトレードオフ、必要になる後続の判断は何か。

## Alternatives Considered

### 選択肢1: 名称

- **Description**：この選択肢の簡単な説明。
- **Pros**：得られたはずの利点。
- **Cons**：却下した理由。

## References

- 関連する RFC、設計文書、ベンチマーク結果、議論へのリンク。
```

## 採番とファイル名

- 番号は連番（ADR-001, ADR-002, …）で、廃止しても再利用しない。
- ファイル名は `ADR-NNN-<kebab-case-title>.md` とする。
- 追加したら [ADR インデックス](index.md) に 1 行加える。

## ライフサイクル

- 状態は Proposed、Accepted、Deprecated、Superseded で管理する。
- 決定は実装前に Proposed として起こし、Pull Request でコードと一緒にレビューする。
- 置き換えるときは、新旧の ADR を `Superseded by ADR-NNN` で相互リンクする。

## 書き方の指針

- 決定の背景を知らない将来の読み手に向けて書き、なぜそうしたかを説明する。
- コードは何を作ったかを示すが、なぜかは ADR が説明する。
- あらゆる判断には負の帰結があり、それを記すことが信頼につながるため、トレードオフを正直に書く。
- ベンチマーク結果やコスト見積りなど、定量的な根拠があれば含める。
- 却下した選択肢は、省くと検討不足に見えるため省かない。
- ADR を実装後の文書化にせず、判断の道具として実装前に起こす。
