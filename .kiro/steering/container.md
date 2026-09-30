---
inclusion: fileMatch
fileMatchPattern: ["**/{Dockerfile,Dockerfile.*,*.Dockerfile,*.dockerfile}", "**/.dockerignore", "**/{compose,docker-compose}*.{yml,yaml}"]
name: container
description: Dockerfile、.dockerignore、compose.yaml や docker-compose.yml を新規作成または編集するときに、どの docs の規約を読むかを示す。
---

# コンテナ定義を扱うとき

コンテナ定義の規約の正文は `docs/container/index.md` から読む。

## 行動指針

- Dockerfile や Compose ファイルを変更する前に、該当する規約文書を読む。
- ベースイメージとサービスのイメージは、タグと digest を固定したまま変更する。
- Dockerfile を変えたら hadolint、Compose を変えたら `docker compose config` で検証する。

## どの docs を読むか

- Dockerfile や .dockerignore を書く、または直すとき：`docs/container/dockerfile-conventions.md`
- compose.yaml や docker-compose.yml を書く、または直すとき：`docs/container/compose.md`
