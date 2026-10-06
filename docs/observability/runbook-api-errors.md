---
type: Runbook
title: API エラーのログイベント
description: API の例外処理（ApiExceptionHandler）が出す WARN と ERROR のログイベントについて、発生原因と運用者の対応を定める運用定義。そのログに対応するとき、そのログを追加または変更するときに使う。
tags: [runbook, observability, logging, operations, web-api]
---

# API エラーのログイベント

API の例外処理は、5xx を `ERROR`、入力検証の 400 を `WARN`、その他の 4xx を `INFO` で記録する。
この文書は `WARN` 以上の event の運用定義で、項目は[運用者が対応するログイベントの管理](log-operational-events.md)に従う。
`API client error`（`INFO`）は運用定義を要求しないため書かない。

## Unhandled API exception

- **event 名**：`Unhandled API exception`
- **レベル**：`ERROR`
- **事象**：API の request の処理で 5xx を返した。
- **属性**：`http.response.status_code`、`exception.type`、`exception.message`、`exception.stacktrace`
- **利用者への表示**：`type` は `about:blank`、message key は `problem.title.<status>`
- **発生原因**：実装の不備（未処理の例外、応答の変換の失敗、戻り値の制約違反）と、回復しない通信や依存先のエラー。
- **対応**：`exception.type` と trace で発生箇所を特定し、当日中に復旧する。実装の不備なら修正する。

## API validation failed

- **event 名**：`API validation failed`
- **レベル**：`WARN`
- **事象**：API の入力検証で 400 を返した。
- **属性**：`http.response.status_code`
- **利用者への表示**：`type` は `/problems/validation-error`、message key は `problem.title.validation-error`
- **発生原因**：SPA の入力検証の漏れか、SPA と API の制約の不一致。
- **対応**：trace から request の path を特定し、数営業日以内に該当画面の検証を API の制約に合わせる。
