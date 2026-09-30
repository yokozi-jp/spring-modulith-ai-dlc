---
inclusion: always
name: adr-decision-record
description: 設計やアーキテクチャ上の判断をするときに ADR を起こすための案内。規約の正文は docs/adr/conventions.md にある。
---

# 判断があったら ADR を残す

ADR の運用ルールの正文は docs にある。
この steering は行動指針と読む文書だけを示す。

## 行動指針

- 後戻りしにくい判断、複数のモジュールに影響する判断、議論のあった判断をするときは ADR を起こす。
- ADR は判断を提案または実装する時点で Proposed として起こし、事後の文書化にしない。
- 判断の根拠を会話やコミットメッセージだけに残さない。
- ADR が Accepted または Superseded になったら、対応する docs の規約文書を同じ変更で更新する。

## 読む docs

- `docs/adr/conventions.md`：ADR を作る基準、記録先、採番、ライフサイクル（ADR を起こす前に読む）
- `docs/adr/adr-template.md`：ADR の書式と書き方の指針（ADR を書くときに読む）
- `docs/adr/index.md`：既存の ADR の一覧（設計判断の前に既存の判断を確認するときに読む）
