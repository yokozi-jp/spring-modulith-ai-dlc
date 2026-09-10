---
inclusion: always
name: adr-decision-record
description: 重要な設計・アーキテクチャ上の判断を下したときに Architecture Decision Record（ADR）を残す規約。ADR を作る/作らない基準、記録先（横断的な判断は docs/adr、インテント固有は AI-DLC の record dir）、書式（AI-DLC 同梱テンプレートに準拠）、採番、ライフサイクル、運用手順を定める。技術選定・境界設計・API 契約・認証方式・データ方針など、後戻りしにくい判断や議論のあった判断をするとき、またその判断を提案・実装するときに使用する。
---

# 判断があったら ADR を残す

重要な設計・アーキテクチャ上の判断を下したときは、Architecture Decision Record（ADR）を残す。
これは人にもエージェントにも適用する。
判断を提案・実装する時点で ADR を起こし、事後の文書化にしない。

## ADR を作る基準

次のいずれかに当てはまる判断は ADR にする。

- 実装後に戻しにくい（DB、API 契約、フレームワーク、言語、認証方式など）。
- 複数のモジュールやチームに影響する。
- コスト、性能、セキュリティに大きく影響する。
- 議論や異論があった（合意形成の理由を残す価値がある）。
- 過去のアーキテクチャ方針を変更する。

## ADR を作らないもの

- 変数名やコード整形などの日常的な実装選択。
- 自明に可逆な決定。
- すでにチーム規約や steering に文書化されている標準。

## 記録先

判断のスコープで置き場所を分ける。

- **プロジェクト横断・ワークフロー外の判断**は `docs/adr/` に追加する。
  技術選定、全体のアーキテクチャ、時刻や認証の方針などが該当する。
- **インテント固有の設計判断**は AI-DLC が inception 実行時に
  `<record>/inception/domain-design/decisions.md` へ生成する。
  これはそのまま AI-DLC に任せ、`docs/adr/` へ二重に書かない。
- インテント固有の ADR のうち、後からプロジェクト横断で効くと判明したものだけを、
  `docs/adr/` へ昇格（採番し直して記録）する。昇格は手動のキュレーションとする。

## 書式

書式は AI-DLC 同梱の
`.kiro/knowledge/aidlc-architect-agent/adr-template.md` に準拠する。
新しいテンプレートを作らない。

- 見出し構成：Status / Date / Context / Decision / Consequences
  （Positive・Negative・Neutral）/ Alternatives Considered / References。
- Context には、判断を必要とした制約と背景を書く。
- Decision は能動態で言い切る。
- Consequences は負の帰結も正直に書く。
- Alternatives Considered には、却下した選択肢とその理由を書く。

## 採番とファイル名

- `docs/adr/` の ADR は連番（`ADR-001`, `ADR-002`, …）で、廃止しても番号を再利用しない。
- ファイル名は `ADR-NNN-<kebab-case-title>.md`。
- 追加したら `docs/adr/index.md` の一覧へ 1 行加える。

## ライフサイクル

- 状態は Proposed / Accepted / Deprecated / Superseded で管理する。
- 決定は実装前に Proposed として起こし、PR でコードと一緒にレビューする。
- 置き換えるときは、新旧の ADR を `Superseded by ADR-NNN` で相互リンクする。

## エージェントへの指示

このリポジトリで作業するエージェントは、上記の基準に当てはまる判断を
提案または実装するとき、対応する ADR を `docs/adr/` に作成（または Proposed として提案）する。
判断の根拠を会話やコミットメッセージだけに残して、ADR を省略しない。
