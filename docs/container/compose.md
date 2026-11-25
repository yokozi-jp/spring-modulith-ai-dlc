---
type: Convention
title: Compose の作り方
description: Docker Compose ファイルのサービス分離、イメージの固定、起動順序と healthcheck、restart、環境変数と Secrets、DB のロール、volumes、ports、networks、profiles、外部システムの偽物（WireMock）、build、検証手段を定める規約。compose.yaml や docker-compose.yml を書く、または直すときに読む。
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
- シェルの構文を含む検査は `CMD-SHELL` で書き、`CMD` の 1 要素にシェルの文を詰めない。
  podman の互換 API は、その要素を空白で分割する（[ADR-093](../adr/ADR-093-support-kiro-web-sandbox-as-development-environment.md)）。

## restart ポリシー

- 常駐サービスには `restart: unless-stopped`（または本番では `always`）を設定し、障害時に復帰させる。

## 環境変数と Secrets

- 機密情報は環境変数に直書きせず、`secrets` を使う。
- 環境変数の優先順位（`.env`、shell、`environment`、CLI）を理解して使う。
- 環境ごと（development、testing、production）に `.env` ファイルを分ける。`.env` はコミットしない。
- 変数展開（interpolation）の挙動を理解する。一時的な上書きは CLI（`-e` や `docker compose run -e`）で行う。

アンチパターン：パスワードや API キーを `environment:` に平文で書き、リポジトリにコミットする。

## DB のロール

- PostgreSQL のイメージの `POSTGRES_USER` は superuser になるため、コンテナの初期化（`/docker-entrypoint-initdb.d`）だけに使う。
- アプリやマイグレーションが接続するロールは、初期化のスクリプトで superuser でも `BYPASSRLS` でもないロールとして作る。
  `docker/compose.yml` と `docker/compose-test.yml` では `docker/initdb` がマイグレーション用ロールとアプリ用ロールを作る（[DB接続情報とロール分離](../database/connections.md)）。
- superuser のパスワードもほかの機密と同じく `secrets` で渡す。

アンチパターン：マイグレーション用ロールを `POSTGRES_USER` にして superuser のまま使い、表の所有者に RLS が効かない状態にする。

## volumes

- 永続データは named volume に置く。ホストと共有したい設定やソースは bind mount を使い分ける。
- 読み取り専用でよいマウントには `:ro` を付ける。
- コンテナ内のデータをボリュームに載せずに永続化しない。

アンチパターン：DB データをボリュームに載せず、コンテナ削除で消える状態にする。

## ports

- 必要なポートだけ公開する。外部に晒したくないサービスは `ports` を張らず、ネットワーク内通信だけにする。
- ローカル限定で使うなら `127.0.0.1:5432:5432` のようにループバックへバインドする。
- compose-test（`docker/compose-test.yml`）のホストのポートは、`127.0.0.1:${TEST_PG_PORT:-5433}:5432` のように slot が渡す変数で書き、slot 0 の値を既定値にする（[compose-test の slot](../tooling/test-slots.md)）。

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

- 複数の用途が使う service には profile を付けない。
  例：compose-test の WireMock は、`task e2e` とバックエンドのスモークが使うため、profile を付けない。
  Spring のテストは、同じスタブ（`docker/wiremock`）をプロセス内の WireMock で読む（[バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)）。

## 外部システムの偽物

外部システムは、本番のコードで偽らず、ローカルとテストの compose に置く WireMock で偽る（[ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md)）。
例は `docker/compose.yml` と `docker/compose-test.yml` の `wiremock` で、決済代行と SES v2 の API の両方を 1 つのコンテナで偽る。

- WireMock のイメージはタグと digest で固定し、テストの依存の `org.wiremock:wiremock-standalone` の版の正本にする。jar の版は `backend/build.gradle` が `docker/compose-test.yml` のイメージの tag から読む。`docker/compose.yml` のイメージは手で `docker/compose-test.yml` と同じにする。
- `command` に `--disable-http2-plain` を付ける。
  JDK の `HttpClient` は平文の HTTP で HTTP/2 への upgrade（h2c）を試み、WireMock（Jetty）では本文付きの POST が切れるためである。
- 成功のスタブは `docker/wiremock/mappings/` に置き、`/home/wiremock/mappings` へ `:ro` でマウントする。
  2 つの compose ファイルと JVM の中の結合テストが同じファイルを読む。
- healthcheck は `/__admin/health` を `curl` で確かめる。
- ポートは `127.0.0.1` に限って公開する（開発用 8090、compose-test は slot 0 で 8082）。
  backend は compose の network の中から service 名（`http://wiremock:8080`）で呼ぶ。
- SES v2 の API は、backend の環境変数 `AWS_ENDPOINT_URL_SESV2` で WireMock に向ける（[ADR-125](../adr/ADR-125-send-mail-with-ses-v2-api-and-fake-it-with-wiremock.md)）。
  `SesV2ClientConfig` が、Spring の設定にあるこの環境変数を Client の接続先にする。
  同じ backend に、ダミーの `AWS_ACCESS_KEY_ID` と `AWS_SECRET_ACCESS_KEY` と、`AWS_REGION` を渡す。
- 手元で送ったメールは、開発用の WireMock（`http://127.0.0.1:8090`）の要求の記録から読む。
  条件の書き方は[E2E テストの方針と書き方](../e2e/testing-strategy.md#送ったメールを読む)にある。
- 失敗と拒否は、マッピングファイルに置かず、テストが管理 API で実行中に足す。
- WireMock は本番の構成に置かない。

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
