---
type: Runbook
title: Renovate
description: mise.toml と mise.lock に固定した道具の版を更新する Renovate の範囲、GitHub App のインストール、PR を merge する前の確認、App をインストールした後に確かめた結果と未確認の項目、設定を変えたときの検査。Renovate の PR が出たとき、renovate.jsonc を変えるとき、App をインストールするときに読む。
tags: [tooling, runbook, renovate, mise]
---

# Renovate

## 要約

- Renovate は、mise の道具の版を更新する PR だけを出す（[ADR-124](../adr/ADR-124-update-mise-pinned-versions-with-renovate.md)）。設定は `renovate.jsonc` である。
- gradle、npm、github-actions、docker、docker-compose は Dependabot が更新する（[ADR-021](../adr/ADR-021-group-dependabot-minor-and-patch-updates.md)）。Renovate は触らない。
- Renovate の PR は自動 merge しない。人が差分を読んで squash merge する。
- PR のタイトルは `chore(deps): ...` で、`Validate PR title` を通る。

## 更新の対象

- `mise.toml` と `mise.lock`：Task、betterleaks、iwe、jq、zizmor。mise manager が `mise.lock` も更新する。
- `mise.toml` の `[tools."http:kiro-cli"]`：`version`。regex manager が Kiro 公式の `index.json` から検出する。branch は `renovate/kiro-cli-*` である。公開日がないので 7 日の待機は掛からず、`mise.lock` も更新されない。
- `.github/workflows/zizmor.yml`：`zizmor-action` の `version`。zizmor の PR で `mise.toml` と一緒に更新する。branch は `renovate/zizmor` である。
- `mise.kiro-web.toml` と `mise.kiro-web.lock`：docker-compose。メジャー更新は出さない（Compose 5 以降はサンドボックスで動かない）。

更新しないもの。

- Java（`corretto-...`）、`http:jdtls`、`http:lombok`。手で上げる（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。
- `.github/` の action の SHA、gradle、npm、docker。Dependabot が更新する。

`minimumReleaseAge` は 7 日で、Dependabot の cooldown に合わせる。Kiro CLI を除き、公開から 7 日たたない版は PR にならない。

## PR を merge する前に

- 差分が「更新の対象」に挙げたファイルに限られ、版と checksum の対応が崩れていないことを見る。
- betterleaks と zizmor の PR は、検査器そのものの更新である。検出が壊れても「指摘なし」の成功と見分けられないので、リリースノートを読み、`task lint-betterleaks` などで検出が動くことを確かめる。
- Kiro CLI の PR は、リリースノートを読む。PR のブランチで `mise lock --platform linux-x64 http:kiro-cli` を実行して `mise.lock` を commit し、push する（Renovate の regex manager は lock を更新しない）。
- zizmor の PR で `zizmor-action` が新しい版を受け付けないとき（action の SHA が持つ版の一覧にない）は、`zizmor-action` の SHA も上げる。

## インストール（管理者）

リポジトリの管理者が、[Renovate の GitHub App](https://github.com/apps/renovate) を `yokozi-jp/spring-modulith-ai-dlc` だけに許可してインストールする。
App は default branch の設定を読むので、`renovate.jsonc` を main に merge するまで動かない。
`renovate.jsonc` が default branch にあれば onboarding の PR は出ず、`Dependency Dashboard` の issue ができる。

## App をインストールした後に確かめた結果

最初の mise manager の PR（#727 の iwe、#728 の Task）で、次を確かめた。結果は [ADR-124](../adr/ADR-124-update-mise-pinned-versions-with-renovate.md) の Consequences にある。

- `mise.lock` は、版を上げる PR で更新される。
- iwe の PR は、`.mise/locks/npm-iwe-org-iwe/<版>/` を追加する。
- `mise.lock` は `linux-x64` の記録だけを保つ。

次は未確認である。最初の docker-compose の PR で確かめる。

- `mise.kiro-web.lock` が、版を上げる PR で更新される。更新されなければ、手で `mise lock` を実行して push する。

## 設定を変えたとき

手元で検査する。

```bash
npx --yes -p renovate@44 renovate-config-validator renovate.jsonc
GITHUB_COM_TOKEN=$(gh auth token) LOG_LEVEL=debug npx --yes -p renovate@44 renovate --platform=local --dry-run=lookup
```

2 行目は、検出した依存と更新を debug ログに出す。`GITHUB_COM_TOKEN` がないと GitHub の依存は `github-token-required` で飛ばされる。
