---
inclusion: always
name: adr-decision-record
description: 設計やアーキテクチャ上の判断をするときに ADR を起こすための案内。規約の正文は docs/adr/conventions.md にある。
---

# 判断があったら ADR を残す

ADR の運用ルールの正文は `docs/adr/conventions.md` にある。
この steering は、ADR を起こす場面の判定基準と読む文書だけを示す。

## ADR を起こす場面

次のいずれかに当てはまる判断をするときは、ADR を起こす。

- 実装後に戻しにくい（DB、API 契約、フレームワーク、言語、認証方式など）。
- 複数のモジュールやチームに影響する。
- コスト、性能、セキュリティに大きく影響する。
- 議論や異論があった（合意形成の理由を残す価値がある）。
- 過去のアーキテクチャ方針を変更する。

当てはまったら、起こす時期、書式、規約文書との同期を `docs/adr/conventions.md` で確認してから書く。

## 読む docs

- `docs/adr/conventions.md`：ADR を作る基準、記録先、採番、ライフサイクル（ADR を起こす前に読む）
- `docs/adr/adr-template.md`：ADR の書式と書き方の指針（ADR を書くときに読む）
- `docs/adr/index.md`：既存の ADR の一覧（設計判断の前に既存の判断を確認するときに読む）
