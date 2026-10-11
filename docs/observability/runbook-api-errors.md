---
type: Runbook
title: API エラーのログイベント
description: API の例外処理（ApiExceptionHandler と SecurityConfig）が出す WARN と ERROR のログイベントについて、発生原因と運用者の対応を定める運用定義。そのログに対応するとき、そのログを追加または変更するときに使う。
tags: [runbook, observability, logging, operations, web-api]
---

# API エラーのログイベント

API の例外処理は、5xx を `ERROR`、入力検証の 400 を `WARN`、その他の 4xx を `INFO` で記録する。
security の entry point と access denied handler は、例外を例外処理が扱わなかったときに `WARN` で記録する。
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

## Unresolved API security exception

- **event 名**：`Unresolved API security exception`
- **レベル**：`WARN`
- **事象**：API の未認証または認可の拒否を例外処理が扱わず、401 または 403 で sendError した。
- **属性**：`http.response.status_code`、`exception.type`、`exception.message`、`exception.stacktrace`
- **利用者への表示**：`/error` が返す 401 または 403 の Problem Details で、`type` は `about:blank`、message key は `problem.title.<status>`
- **発生原因**：実装の不備で、例外処理の advice が security の例外を扱っていない。
- **対応**：`exception.type` と trace から例外の型を特定し、数営業日以内に advice が扱うよう修正する。

## Unresolved API security exception after commit

- **event 名**：`Unresolved API security exception after commit`
- **レベル**：`WARN`
- **事象**：API の未認証または認可の拒否を例外処理が扱わず、応答が commit 済みのため sendError しなかった。
- **属性**：`http.response.status_code`、`exception.type`、`exception.message`、`exception.stacktrace`
- **発生原因**：実装の不備で、例外処理の advice が応答の本文を書いた後に失敗した。
- **対応**：`exception.type` と trace から advice の失敗箇所を特定し、利用者が受け取った不完全な応答の request を確かめ、数営業日以内に修正する。
