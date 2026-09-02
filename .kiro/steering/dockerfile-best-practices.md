---
inclusion: fileMatch
fileMatchPattern: "**/{Dockerfile,Dockerfile.*,*.Dockerfile,*.dockerfile}"
name: dockerfile-best-practices
description: Dockerfile を新規作成・編集するときのベストプラクティスとアンチパターン。ベースイメージの選び方、マルチステージビルド、タグと digest の固定、.dockerignore、レイヤーキャッシュ順序、パッケージ導入、非 root 実行、Secrets、COPY/ADD、ENTRYPOINT/CMD、WORKDIR、1 コンテナ 1 concern、検証手段を定める。Docker 公式のビルドベストプラクティスに準拠する。Dockerfile を書く・直すときに使用する。
---

# Dockerfile の作り方

Dockerfile を作成・編集するときは、以下に従う。
すべて Docker 公式のビルドベストプラクティスに基づく（末尾の出典を参照）。

## ベースイメージ

- **信頼できる小さいイメージ**を選ぶ。Docker Official Images、Verified Publisher、Docker-Sponsored Open Source のいずれかのバッジが付いたものを起点にする。
- ビルド用と実行用でイメージを分ける。実行用にはコンパイラやビルドツールを含まない、より小さいイメージを使う。
- 小さいイメージほど転送が速く、脆弱性の対象面が減る。

アンチパターン：出所不明のイメージや、汎用の巨大 OS を何となく使う。

## マルチステージビルド

- ビルド段と実行段を `FROM ... AS build` と `FROM ...` に分け、最終イメージには実行に必要なものだけを残す。
- ビルド段の成果物だけを `COPY --from=build` で実行段に持ち込む。

アンチパターン：`gcc`、JDK、SDK、テストツールなどを本番イメージに残す。

## バージョンの固定

- タグを明示する（`FROM alpine:3.21` のように）。`latest` に全面依存しない。
- 再現性が要るなら digest も pin する（`FROM alpine:3.21@sha256:...`）。digest を固定すると、タグが差し替わっても同じイメージでビルドできる。
- digest 固定は自動のセキュリティ更新を止める副作用があるため、Dependabot や Docker Scout で更新を追う。

アンチパターン：`FROM xxx:latest` に依存し、いつビルドしても中身が変わりうる状態にする。

## ビルドコンテキストと .dockerignore

- `.dockerignore` で不要物をコンテキストから除外する。転送量を減らし、秘密の混入を防ぐ。
- 少なくとも `.git`、`node_modules`、ビルド生成物、`.env` などの秘密ファイルを除外する。

アンチパターン：`.git` や `node_modules`、認証情報を含むディレクトリをまるごとコンテキストへ送る。

## レイヤーキャッシュの順序

- 変更頻度の低い処理を上に、高い処理を下に置く。依存の解決を先に済ませ、アプリのソースは後で `COPY` する。
- ソースを一度に入れる `COPY . .` を最初に置かない。ソースを変えるたびに以降の層のキャッシュが無効になる。

アンチパターン：Dockerfile の冒頭付近で `COPY . .` してから依存を解決する。

## パッケージ導入

- 必要なものだけ入れる。「あると便利」で debug ツールや推奨パッケージまで入れない。
- apt では `update` と `install` を同じ `RUN` にまとめ、`--no-install-recommends` を付け、最後に `rm -rf /var/lib/apt/lists/*` する。

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
    package-bar \
    package-baz \
    && rm -rf /var/lib/apt/lists/*
```

- alpine では `apk add --no-cache` を使う。
- 複数行の引数は英数順に並べ、重複と差分レビューの手間を減らす。

アンチパターン：`RUN apt-get update` と `RUN apt-get install` を別の `RUN` に分ける（キャッシュ不整合で古いパッケージが入る）。

## 権限（非 root 実行）

- サービスが特権なしで動くなら `USER` で非 root ユーザーに切り替える。
- ユーザーとグループを作ってから `USER` を指定する。再現性が要るなら UID/GID を明示する。
- `sudo` の導入・使用は避ける。TTY やシグナル転送の挙動が読めない。

アンチパターン：常時 root で実行する。

## Secrets

- ビルド時に必要な秘密は BuildKit の secret mount（`RUN --mount=type=secret,...`）で渡す。
- 実行時の秘密はイメージに焼き込まず、オーケストレータ（ECS の environment/secrets など）から注入する。

アンチパターン：`ARG TOKEN=...` や `ENV PASSWORD=...` で秘密を焼き込む。`ENV` の値は後で unset しても中間層に残り、`docker history` 等から読める。

## COPY と ADD

- 通常のファイルコピーは `COPY` を使う。ビルド段間のコピーも `COPY --from=...`。
- `ADD` はリモート URL の取得（`--checksum` 付き）や tar の自動展開など、その機能が要るときに限る。

アンチパターン：何でも `ADD` で済ませる。

## ENTRYPOINT と CMD

- exec 形式（JSON 配列）を基本にする（`ENTRYPOINT ["java", "-jar", "app.jar"]`）。
- exec 形式なら、アプリがコンテナの PID 1 になり、Unix シグナルを受け取れる（graceful shutdown が効く）。
- `ENTRYPOINT` に主コマンド、`CMD` に既定の引数、という役割分担にする。

アンチパターン：shell 形式（`ENTRYPOINT java -jar app.jar`）で余計な shell を PID 1 に挟み、シグナルがアプリに届かない。

## WORKDIR

- 絶対パスで `WORKDIR` を指定する。
- `RUN cd /app && ...` の連発をやめ、`WORKDIR` で作業ディレクトリを定める。

## 1 コンテナ 1 concern

- 1 つのコンテナは 1 つの関心事だけを持つ。Web、DB、キャッシュは別コンテナに分ける。
- 分離するとスケールと再利用がしやすくなる。依存するコンテナ間はネットワークでつなぐ。

アンチパターン：Web と DB と cron を 1 コンテナに詰め込む。

## HEALTHCHECK

- 長時間動くサービスには `HEALTHCHECK` を定義し、プロセス生存だけでなくアプリの応答可否を見る。

## 検証

- Dockerfile は hadolint で lint し、`docker build --check` で公式チェックにかける。
- CI でイメージをビルド・テストする。base image の更新は Docker Scout や Dependabot で追う。

## アンチパターンまとめ

- 出所不明・巨大な汎用 OS をベースにする。
- ビルドツールを本番イメージに残す。
- `FROM xxx:latest` に全面依存する。
- コンテキストに `.git`・`node_modules`・secrets を送る。
- 冒頭で `COPY . .` する。
- 不要なパッケージや推奨パッケージまで入れる。
- `apt update` と `apt install` を別 `RUN` に分ける。
- 常時 root で動かす。
- `ARG`/`ENV` に秘密を焼き込む。
- 何でも `ADD` する。
- shell 形式の `ENTRYPOINT`/`CMD` を使う。
- `RUN cd ... && ...` を多用する。
- 1 コンテナに複数の関心事を詰め込む。

## 出典

- Docker Docs, Building best practices（<https://docs.docker.com/build/building/best-practices/>）
- Docker Docs, Multi-stage builds / Base images / Secrets（Docker Build マニュアル）
