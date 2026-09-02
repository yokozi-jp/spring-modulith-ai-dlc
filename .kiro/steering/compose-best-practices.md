---
inclusion: fileMatch
fileMatchPattern: "**/{compose,docker-compose}*.{yml,yaml}"
name: compose-best-practices
description: Docker Compose ファイル（compose.yaml / docker-compose.yml）を新規作成・編集するときのベストプラクティスとアンチパターン。サービス分離、イメージのタグと digest 固定、起動順序（depends_on と healthcheck condition）、restart ポリシー、環境変数と Secrets、volumes、ports、networks、profiles、build、検証手段を定める。Docker 公式の Compose ドキュメントに準拠する。Compose ファイルを書く・直すときに使用する。
---

# Compose の作り方

Compose ファイルを作成・編集するときは、以下に従う。
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

- 各サービスに `healthcheck` を定義し、`interval`・`timeout`・`retries` を設定する。
- DB なら `pg_isready`、Redis なら `redis-cli ping` のように、実際に応答できるかを見るコマンドにする。

## restart ポリシー

- 常駐サービスには `restart: unless-stopped`（または本番では `always`）を設定し、障害時に復帰させる。

## 環境変数と Secrets

- 機密情報は環境変数に直書きせず、`secrets` を使う。
- 環境変数の優先順位（`.env`、shell、`environment`、CLI）を理解して使う。
- 環境ごと（development・testing・production）に `.env` ファイルを分ける。`.env` はコミットしない。
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

アンチパターン：内部依存（DB・キャッシュ）のポートまで無条件に `0.0.0.0` へ公開する。

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

## build

- `build:` を使うときは `context` を必要最小限のディレクトリに絞り、`dockerfile` を明示する。
- ビルドコンテキスト側に `.dockerignore` を置く。

## 検証

- 変更したら `docker compose config` で構文・参照・変数展開を検証する。
- CI で `docker compose config` を回し、壊れた Compose をマージしない。

## アンチパターンまとめ

- 1 service に複数の関心事を詰め込む。
- サービス間通信に `localhost` を使う。
- タグなし・`:latest` のイメージを使う。
- `depends_on` だけで相手が使えると仮定する。
- healthcheck を定義しない。
- 機密を `environment:` に平文で置き、`.env` をコミットする。
- 永続データをボリュームに載せない。
- 内部サービスのポートまで外部公開する。

## 出典

- Docker Docs, Environment variables best practices（<https://docs.docker.com/compose/how-tos/environment-variables/best-practices/>）
- Docker Docs, Control startup order（<https://docs.docker.com/compose/how-tos/startup-order/>）
- Docker Docs, Secrets in Compose / Use service profiles / Use Compose in production（Docker Compose マニュアル）
