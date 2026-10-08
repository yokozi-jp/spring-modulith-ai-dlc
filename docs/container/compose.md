---
type: Convention
title: Compose の作り方
description: Docker Compose ファイルのサービス分離、イメージの固定、起動順序と healthcheck、restart、環境変数と Secrets、volumes、ports、networks、profiles、外部システムの偽物（WireMock）と管理 API、build、検証手段を定める規約。compose.yaml や docker-compose.yml を書く、または直すとき、外部システムを WireMock で偽るときに読む。
tags: [convention, container, docker, compose]
---

# Compose の作り方

サービスは関心事ごとに分け、イメージをタグと digest で固定する。
依存先には healthcheck を定義して `condition: service_healthy` で待ち、機密は `secrets` で渡す。
公開するポートは必要最小限にし、変更後は `docker compose config` で検証する。

Compose ファイルを作成、編集するときは、以下に従う。
すべて Docker 公式の Compose ドキュメントに基づく（末尾の出典を参照）。

## サービス分離（1 コンテナ 1 concern）

- サービスは関心事ごとに分ける。アプリ、DB、キャッシュはそれぞれ独立した service にする。
- サービス間はサービス名で名前解決する（同一ネットワークなら `postgres:5432` のように）。

アンチパターン：1 つの service に複数の役割を詰め込む。ホスト名に `localhost` を使ってサービス間通信を書く。

## イメージの固定

- `image:` はタグを明示し、再現性が要るなら digest も pin する（`postgres:18-alpine@sha256:...`）。
- 更新は Dependabot（`package-ecosystem: docker-compose`）などで追う。

アンチパターン：`image: postgres`（タグなし）や `:latest` で、起動ごとに中身が変わりうる状態にする。

## 起動順序

- 依存関係は `depends_on` で示す。ただし `depends_on` は「コンテナが起動したか」までしか見ない。
- 「相手が受け付け可能になったか」を待つには、依存先に `healthcheck` を定義し、`condition: service_healthy` を使う。

```yaml
depends_on:
  postgres:
    condition: service_healthy
```

- それでもアプリ側に接続リトライを持たせる。healthcheck は起動時点の保証であり、実行中の切断までは防げない。

アンチパターン：`depends_on` だけで「相手が使える」と仮定し、起動直後に接続して失敗する。

## healthcheck

- 各サービスに `healthcheck` を定義し、`interval`、`timeout`、`retries` を設定する。
- DB なら `pg_isready`、Redis なら `redis-cli ping` のように、実際に応答できるかを見るコマンドにする。

## restart ポリシー

- 常駐サービスには `restart: unless-stopped`（または本番では `always`）を設定し、障害時に復帰させる。

## 環境変数と Secrets

- 機密情報は環境変数に直書きせず、`secrets` を使う。
- 環境変数の優先順位（`.env`、shell、`environment`、CLI）を理解して使う。
- 環境ごと（development、testing、production）に `.env` ファイルを分ける。`.env` はコミットしない。
- 変数展開（interpolation）の挙動を理解する。一時的な上書きは CLI（`-e` や `docker compose run -e`）で行う。

アンチパターン：パスワードや API キーを `environment:` に平文で書き、リポジトリにコミットする。

## volumes

- 永続データは named volume に置く。ホストと共有したい設定やソースは bind mount を使い分ける。
- 読み取り専用でよいマウントには `:ro` を付ける。
- コンテナ内のデータをボリュームに載せずに永続化しない。

アンチパターン：DB データをボリュームに載せず、コンテナ削除で消える状態にする。

## ports

- 必要なポートだけ公開する。外部に晒したくないサービスは `ports` を張らず、ネットワーク内通信だけにする。
- ローカル限定で使うなら `127.0.0.1:5432:5432` のようにループバックへバインドする。

アンチパターン：内部依存（DB やキャッシュ）のポートまで無条件に `0.0.0.0` へ公開する。

## networks

- 既定の bridge ネットワークで足りることが多い。分離が要るとき（フロントとバックの隔離など）に明示的なネットワークを定義する。

## profiles

- 用途で起動対象を切り替えるには `profiles` を使う。
- 例：既定の `up` では DB とキャッシュだけ起動し、フルスタック確認のときだけ `--profile fullstack` でアプリも起動する。

```yaml
services:
  backend:
    profiles: ["fullstack"]
```

## 外部システムの偽物（WireMock）

HTTP の外部システムは、本番のコードでなく compose の WireMock のサービスで偽る（[ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md)）。
成功の応答はマッピングファイルで返し、失敗は管理 API で実行中に足す。

- イメージは他のサービスと同じくタグと digest で固定する（`wiremock/wiremock:3.13.2@sha256:d737d2de3664a7e1bf96f73a7bd48a0d47d61988f7ca88a6e51ea44b8c1f687d`）。
  backend のテストの依存 `org.wiremock:wiremock-standalone` も同じ版にする。
- マッピングは `./wiremock/mappings:/home/wiremock/mappings:ro` で読み取り専用にマウントする。
  開発用の compose、compose-test、backend の結合テストが、そこにある成功のスタブを共有する。
- healthcheck は `curl --fail --silent http://localhost:8080/__admin/health` にし、backend は `condition: service_healthy` で待つ。
- backend はサービス名（`http://wiremock:8080`）で呼ぶ。
  ホストへはループバックにだけ公開し、`docker/compose.yml` は 8090、`docker/compose-test.yml` は 8082 にする。
  compose-test では profile を付けないため、`task test` と `task e2e` が起動する。
- `--disable-http2-plain` を渡す。
  JDK の `HttpClient` が平文の HTTP で HTTP/2 への upgrade を試み、Jetty が本文付きの POST を切るためである。

### 管理 API で応答を切り替える

- 失敗は、共有のスタブの `priority`（`10`）より小さい値（優先度が高い）で、一つの要求のキー（ここでは `Idempotency-Key`）だけに一致するスタブにし、`POST /__admin/mappings` で足す。
- スタブには自分で決めた `id` を付け、終わったら `DELETE /__admin/mappings/{id}` で取り除く。
- マッピングのマウントは `:ro` のため、足したスタブを永続化しない。

ローカルの 8090 で、一つの注文の請求だけを 503 にして戻す例は次のとおりである。

```bash
curl --fail -X POST http://127.0.0.1:8090/__admin/mappings -H 'Content-Type: application/json' \
  -d '{"id":"<stub-id>","priority":1,"request":{"method":"POST","url":"/v1/charges","headers":{"Idempotency-Key":{"equalTo":"<orderId>"}}},"response":{"status":503}}'
curl --fail -X DELETE http://127.0.0.1:8090/__admin/mappings/<stub-id>
```

遅延を試すときは、成功の応答に `fixedDelayMilliseconds` を付ける。

## build

- `build:` を使うときは `context` を必要最小限のディレクトリに絞り、`dockerfile` を明示する。
- ビルドコンテキスト側に `.dockerignore` を置く。

## 検証

- 変更したら `docker compose config` で構文、参照、変数展開を検証する。
- CI で `docker compose config` を回し、壊れた Compose をマージしない。

## アンチパターンまとめ

- 1 service に複数の関心事を詰め込む。
- サービス間通信に `localhost` を使う。
- タグなしや `:latest` のイメージを使う。
- `depends_on` だけで相手が使えると仮定する。
- healthcheck を定義しない。
- 機密を `environment:` に平文で置き、`.env` をコミットする。
- 永続データをボリュームに載せない。
- 内部サービスのポートまで外部公開する。

## 出典

- Docker Docs, Environment variables best practices（<https://docs.docker.com/compose/how-tos/environment-variables/best-practices/>）
- Docker Docs, Control startup order（<https://docs.docker.com/compose/how-tos/startup-order/>）
- Docker Docs, Secrets in Compose / Use service profiles / Use Compose in production（Docker Compose マニュアル）
- WireMock Docs, Running in Docker（<https://wiremock.org/docs/standalone/docker/>）
- WireMock Docs, Admin API Reference（<https://wiremock.org/docs/standalone/admin-api-reference/>）
