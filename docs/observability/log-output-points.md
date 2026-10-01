---
type: Convention
title: 処理ごとに記録するログ
description: 起動時、HTTP アクセス、一括処理で記録するログの時点と項目、フロントエンドでログを出力しない規則を定める規約。起動処理、HTTP のログ出力、大量件数を扱う処理を実装するとき、ブラウザでエラーを収集したくなったときに読む。
tags: [convention, observability, logging, future-arch-guidelines]
---

# 処理ごとに記録するログ

起動時は、稼働しているビルドと起動の各段階を `INFO` で記録する。
HTTP アクセスログは要求時と応答時に記録し、ヘルスチェックを除く。
一括処理は開始、終了、処理件数、進捗を記録し、1件ごとにはログを出さない。
フロントエンドはブラウザの console へログを出力しない。

## 起動時

起動ログから、稼働しているサービス、ビルド、実行環境を特定できるようにする。

- **サービス名と実行環境**：全ログに付く `service.name` と環境名で特定する。
- **バージョンと Git commit**：ビルド工程で生成した値を使い、ソースコードへ手書きしない。
- **ビルド時刻**：UTC で記録する（[ADR-006](../adr/ADR-006-utc-instant-absolute-time-policy.md)）。

設定読み込み、DB 接続、待ち受け開始のような起動の段階を `INFO` で記録し、起動に失敗した段階を特定できるようにする。
Spring Boot が出力する起動ログで足りる段階は、アプリケーションから重ねて出力しない。
起動ログも他のログと同じ一行一 JSON で出力し、人が読むための別形式にしない。

設定値をログへ出す場合は、allowlist で許可した値だけにする。
秘密情報と接続文字列を出力しない。

## HTTP アクセスログ

アクセスログは、要求の受信時と応答の送信時の両方で記録する。
`/actuator/health` 配下へのアクセスは記録しない。
ヘルスチェック以外は、HTTP メソッドで除外しない。

要求時と応答時の両方に、次の属性を記録する。

- **HTTP メソッド**：`http.request.method`
- **route**：`http.route`
- **URL パス**：`url.path`
- **利用者の内部 ID**：`user.id`（ログイン済みの場合だけ）

応答時には、次の属性も記録する。

- **HTTP status**：`http.response.status_code`
- **処理時間**：`http.server.request.duration`（秒）

時刻、レベル、trace ID、span ID はフレームワークが付けるため、アクセスログの実装で追加しない。
`url.path` には query string と URL fragment を含めない。
送信元 IP アドレスと User-Agent は allowlist にないため記録しない。
記録する場合は、allowlist の変更として扱う。

アクセスログを追加する変更では、これらの属性を allowlist へ加える。
禁止する値と allowlist は[可観測性データの規約](conventions.md)に従う。

## 一括処理

大量の件数を順に処理する定期実行やイベント処理では、次を `INFO` で記録する。

- **開始**：処理名と、allowlist で許可したパラメータ。
- **終了**：結果、処理時間、対象件数、成功件数、失敗件数、スキップ件数。
- **進捗**：一定件数（例：1万件）ごとの処理済み件数。

対象が0件で処理を終えた場合も、0件であることを終了ログに記録する。
ループ内で1件ごとにログを出力しない。

## フロントエンド

SPA はブラウザの console へログを出力しない。
この規則は Oxlint の restriction カテゴリに含まれる `no-console` で検出する。

ブラウザで発生したエラーを収集する必要が生じた場合は、収集先とデータの扱いを ADR で決めてから導入する。

## 出典

- フューチャー株式会社「ログ設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forLog/log_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
