---
type: ADR
title: 'ADR-037: AI-DLC フレームワークの利用をやめる'
description: AI-DLC フレームワークへの依存を撤去し、ADR 書式・運用をリポジトリ内に自立させる決定。
tags: [adr, governance, workflow, documentation]
---

# ADR-037: AI-DLC フレームワークの利用をやめる

## Status

Proposed

## Date

2026-09-30

## Context

このリポジトリは開発ワークフローとして AI-DLC（AI-Driven Development Life Cycle）を前提にしてきたが、運用してみて次の問題が重なった。

- フローが重く、段階を踏む手順の負荷に対して得られる進みが見合わなかった。
- 生成されるドキュメントは量が多いわりに参照や更新の労力が高く、資産として活かしにくかった。
- 設計から実装へ移る段階で生成ドキュメントの意図が失われ、ドキュメントと実装の橋渡しが機能せず、実装がその意図どおりに進まなかった。
- AI-DLC の TypeScript コードにバグがあり、その対処で Kiro のクレジットをかなり消費した。

pstack や matt pocock のスキル群など、別のアプローチを試したい。
その前段として、まず AI-DLC を廃止する。

AI-DLC の痕跡は複数の層に散らばっていた。

- README に AI-DLC への導入とセットアップ節と、自動生成される `aidlc/` ワークスペースの説明があった。
- ADR の書式が AI-DLC 同梱テンプレート（`.kiro/knowledge/aidlc-architect-agent/adr-template.md`）を正本として参照していたが、この実体は git 管理外で、フレームワークを外すと参照先が失われる。
- steering（`adr-decision-record`、`documentation-authoring`）が、インテント固有の設計判断を AI-DLC が inception 実行時に生成する前提で書かれていた。
- Semgrep（Taskfile と CI）と Snyk（`.snyk`）が `aidlc` ディレクトリをスキャン除外し、`.gitignore` は `BEGIN AI-DLC:gitignore` マーカーで管理され、CODEOWNERS は `/aidlc/` の所有者を割り当てていた。
- ランタイムとして Bun を「AI-DLC ランタイム」の名目で導入していた（別途撤去済み）。

AI-DLC を使わなくなった以上、これらの参照は動かない前提を指し続け、特に ADR テンプレートの参照は管理外ファイルへの dangling link になる。

## Decision

AI-DLC フレームワークへの依存を撤去し、ワークフローとドキュメント運用をリポジトリ内で自立させる。

ADR の書式は、リポジトリ内の [`docs/adr/adr-template.md`](adr-template.md) を正本とする。
このテンプレートは、従来 AI-DLC 同梱テンプレートが定めていた見出し構成（Status / Date / Context / Decision / Consequences / Alternatives Considered / References）、採番、ライフサイクルを引き継ぐ。
`docs/adr/conventions.md` と steering `adr-decision-record` の参照先を、この新テンプレートへ切り替える。

インテント固有の設計判断を AI-DLC が生成するという前提を steering から除き、設計判断は横断とワークフロー外のものとして `docs/adr/` に一本化する。

README の AI-DLC セットアップ節、`aidlc/` ディレクトリ説明、AGENTS.md の「AI-DLC /」表記を除く。

Semgrep と Snyk のスキャン除外から `aidlc` を外し、`.kiro` と `.agents`（ツールやエージェント関連のコード）の除外は残す。
`.gitignore` の `BEGIN AI-DLC:gitignore` マーカーを外し、汎用の無視エントリ（logs、node_modules、エディタ生成物）はそのまま残す。
CODEOWNERS から `/aidlc/` の割り当てを外す。
これは [ADR-017](ADR-017-adopt-trunk-based-repository-governance.md) が定めた CODEOWNERS 境界のうち `aidlc` を撤去するものであり、他の境界（backend、frontend、docker と infrastructure、`.kiro`、GitHub 設定）は維持する。

リポジトリ名 `spring-modulith-ai-dlc` は変更しない。
改名は URL、clone、`author`、パッケージ名など影響範囲が広く、この判断の対象外とする。

## Consequences

### Positive

- ADR 書式の正本がリポジトリ内に入り、git 管理外ファイルへの dangling link が解消する。
- ドキュメントと設定が、使っていないフレームワークの前提を指さなくなり、実態と一致する。
- スキャン除外が縮み、`aidlc` を口実に検査対象から外れる範囲がなくなる。

### Negative

- AI-DLC の inception など、フレームワークが提供していたワークフロー上の型は失われ、設計判断の起こし方は `docs/adr/` への手動記録に一本化する。

### Neutral

- `aidlc/` は元から自動生成物で git 追跡外だったため、ディレクトリ実体の削除は伴わず、撤去は文書、設定、除外ルールの変更にとどまる。
- 過去の ADR（ADR-012、ADR-017）は当時の判断の記録として本文を保持する。
  ADR-012 の「AI-DLC」という限定表現だけは、エージェント駆動という不変の事実に合わせて言い換えた。

## Alternatives Considered

### AI-DLC の記述・設定をそのまま残す

- **Description**：フレームワークを使わなくなっても、README、steering、除外設定、テンプレート参照を現状維持する。
- **Pros**：変更が不要。
- **Cons**：ADR テンプレートの参照が git 管理外ファイルへの dangling link のまま残り、ドキュメントが使っていない前提を指し続ける。

### ADR テンプレートを廃し、書式を conventions.md に統合する

- **Description**：独立したテンプレートを作らず、見出し構成の説明を `conventions.md` の中だけに置く。
- **Pros**：ファイルが一つ減る。
- **Cons**：従来テンプレートが持っていた書き方の指針や記入例まで conventions に混ざり、運用ルールと書式ガイドの役割が曖昧になる。

## References

- [ADR テンプレート](adr-template.md)
- [ADR の運用ルール](conventions.md)
- [ADR-017: トランクベース開発とリポジトリ保護を採用する](ADR-017-adopt-trunk-based-repository-governance.md)
- [AI-DLC Workflows](https://github.com/awslabs/aidlc-workflows)
