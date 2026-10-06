---
type: Convention
title: 更新の競合（409）の画面の扱い
description: 楽観的ロックの競合、悲観的ロックの取得失敗、一意制約の違反で 409 が返る画面の通知、入力の保持、再送、文言の分担を定める規約。409 が返る画面を作るときに読む。
tags: [convention, frontend, optimistic-locking, problem-details, accessibility]
---

# 更新の競合（409）の画面の扱い

409 の要求を自動で再送せず、入力中の値を捨てず、最新の `lockNo` を黙って差し込まない。
競合したら最新の値を取り直して live region で通知し、form があれば「変更を捨てて最新を表示する」と「最新の版に変更を適用し直す」を利用者に選ばせる。
通知の見出しは backend の `title`、説明と選択肢の文言は操作ごとの catalog に置く。

## バックエンドが返す 409

楽観的ロックの競合、悲観的ロックの取得失敗（`lock_timeout`）、一意制約の違反の 3 つとも、`type` は `about:blank` で、`title` は「競合が発生しました」（英語は `Conflict`）である（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
本文の版の項目名は `lockNo` で、版の送り方（本文か query parameter か）は[楽観的ロック](../web-api/optimistic-locking.md)に従う。

原因を `type` で区別できないので、原因ごとの対応表は作らず、操作ごとに扱う。
更新の 409 は版の競合として扱い、作成の 409 は重複と断定しない文言にする。
原因を画面で区別する必要が出たら、[ADR-058](../adr/ADR-058-use-path-absolute-relative-uri-for-problem-types.md) の形（`/problems/<kebab-case>`）で業務固有の `type` を足す。

## 入力と版

- 409 の要求を自動で再送しない。
- 入力中の値を捨てない。再取得や競合で query の data が変わっても、form の値を自動で作り直さない。
- form の値を query の data から作り直すのは、自分の更新が成功した後と、利用者が「変更を捨てて最新を表示する」を選んだときだけにする。
- 最新の `lockNo` を黙って差し込んで再送しない。form は、編集を始めた版の `lockNo` を持ち続け、自分の更新が成功したら、成功の後の版を新しい起点にする。

## 通知と選択肢

- 競合したら最新の値を取り直し（mutation の後の全 query の無効化が取り直す。[mutation の後の cache](routing-and-state.md#mutationの後のcache)）、live region（`<output>`）で通知する。
- form があれば「変更を捨てて最新を表示する」と「最新の版に変更を適用し直す」を利用者に選ばせる。2 つの button は `<output>` の外（直後）に置き、読み上げに button の名前を混ぜない。
- 「適用し直す」は、利用者の操作を受けてから、form の今の値と最新の `lockNo` で 1 回だけ送る。
- 最新の値を取り直せなかったら、読み込めなかったことを通知し、2 つの button を出さず、form の値と版を変えない。
- form のない操作（確定、取消など）の 409 は、通知して詳細または一覧を取り直す。
- 差分の表示は作らない。

## 分岐と文言

- 分岐は Problem Details の `type` と status で行い、`detail` と `title` の文字列で分岐しない。
- `type` が `about:blank` の 409 は、一般の競合として上と同じに扱う。
- backend の `title` は、通知の見出しとして独立した要素にそのまま表示する。
- 何が起きたかの説明と選択肢の button の文言は、操作ごとに frontend の catalog に置く。
- `title` と catalog の文言を 1 つの文字列に連結しない。
- `problem` か `title` がなければ、catalog の一般の文言を見出しにする。
- `type` を catalog の key に写像しない（[フロントエンドの国際化](i18n.md#problem-details)）。

## 関連資料

- [フロントエンドのルーティングと状態管理](routing-and-state.md)
- [フォームの入力検証](form-validation.md)
- [楽観的ロック](../web-api/optimistic-locking.md)
- [ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)
