---
type: Reference
title: ADR テンプレート
description: このリポジトリの ADR が従う書式のテンプレートと書き方の指針。
tags: [adr, reference, template]
---

# ADR テンプレート

このリポジトリの Architecture Decision Record（ADR）は、以下の書式に従う。
ADR を作る基準、採番とファイル名、ライフサイクルは [ADR の運用ルール](conventions.md) に定める。

## 書式

見出し構成は Status / Date / Context / Decision / Consequences（Positive、Negative、Neutral）/ Alternatives Considered / References とする。
frontmatter に `type: ADR`、`title`、`description`、`tags` を置く。

```markdown
---
type: ADR
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

## 書き方の指針

- 決定の背景を知らない将来の読み手に向けて書き、なぜそうしたかを説明する。
- コードは何を作ったかを示すが、なぜかは ADR が説明する。
- あらゆる判断には負の帰結があり、それを記すことが信頼につながるため、トレードオフを正直に書く。
- ベンチマーク結果やコスト見積りなど、定量的な根拠があれば含める。
- 却下した選択肢は、省くと検討不足に見えるため省かない。
- ADR を実装後の文書化にせず、判断の道具として実装前に起こす。
