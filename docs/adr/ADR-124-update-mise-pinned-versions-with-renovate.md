---
type: ADR
title: 'ADR-124: mise の版の更新だけを Renovate に任せ、Dependabot と併存させる'
description: mise.toml と mise.lock に固定した道具の版（Task、betterleaks、iwe、jq、zizmor、Kiro CLI、docker-compose）を Renovate の GitHub App が更新する PR で上げ、gradle、npm、github-actions、docker、docker-compose は Dependabot に残す決定。自動 merge はしない。
tags: [adr, tooling, renovate, mise, dependabot]
---

# ADR-124: mise の版の更新だけを Renovate に任せ、Dependabot と併存させる

## Status

Proposed

## Date

2026-11-24

## Context

[ADR-090](ADR-090-pin-cli-tools-with-mise-lock.md) で、道具の版を `mise.toml` と `mise.lock` に固定した。
Dependabot は mise を扱えず（`http:` backend も同じ）、固定した版は手で上げるしかない。
上げ忘れた版は古いまま残る。

Renovate は mise manager を持ち、`mise.toml` と `mise.lock` を更新できる。
`http:` の道具は mise manager が `unsupported-datasource` で飛ばすので、Kiro CLI は regex manager と `customDatasources` で扱う。
参照元は [yokozi-jp/agent-software-factory](https://github.com/yokozi-jp/agent-software-factory) の ADR 0018 と ADR 0055 である。

Renovate 44 系を `--platform=local`、`--dry-run=lookup` で動かし、次を確かめた（2026-11-24）。

- `mise.toml` の Task、betterleaks、iwe、jq、zizmor と、`mise.kiro-web.toml` の docker-compose を検出し、更新を見つける。
- `mise.lock` と `mise.kiro-web.lock` を lock file として認識する。
- Java は `corretto-25.0.5.0.10` を `invalid-version` で飛ばす。`http:jdtls` と `http:lombok` は `unsupported-datasource` で飛ばす。
- regex manager が Kiro CLI の版を `index.json` から読み、`zizmor.yml` の `zizmor-action` の `version` も検出する。
- 公開から 7 日たたない zizmor 1.33.1 は `pendingChecks` になり、PR にならない。

## Decision

Mend Renovate の GitHub App を、このリポジトリだけに許可してインストールし、設定を `renovate.jsonc` に置く。

- `enabledManagers` を `mise` と `custom.regex` に絞る。Dependabot が更新する gradle、npm、github-actions、docker、docker-compose の更新を、Renovate は重ねて出さない。`.github/dependabot.yml` と `dependabot-auto-merge.yml` は変えない。
- 自動 merge は設定しない。Renovate の PR は人が merge する。betterleaks のような検査器は、壊れても「指摘なし」の成功と見分けられないためである。
- `minimumReleaseAge` を 7 日にする。Dependabot の cooldown（7 日、[ADR-021](ADR-021-group-dependabot-minor-and-patch-updates.md)）に合わせる。
- PR のタイトルは `semanticCommits` で `chore(deps): ...` にそろえる。
- Kiro CLI は `customDatasources` と regex manager で更新する。公式の `index.json` に公開日がないので、Kiro CLI だけ 7 日の待機を掛けない（`minimumReleaseAgeBehaviour: timestamp-optional`）。regex manager は `mise lock` を実行しないので、Kiro CLI の PR は `mise.lock` を更新しない。人が `mise lock --platform linux-x64 http:kiro-cli` を実行して push する。
- `zizmor.yml` の `zizmor-action` の `version` は、regex manager で検出し、`mise.toml` の zizmor と同じ PR で更新する（ADR-090）。
- docker-compose（`mise.kiro-web.toml`）のメジャー更新は出さない。Compose 5 以降は buildx を要し、サンドボックスの podman で動かない（[ADR-093](ADR-093-support-kiro-web-sandbox-as-development-environment.md)）。

Dependabot と併存させる理由は、Dependabot が gradle、npm、github-actions、docker、docker-compose で grouping と auto-merge を設定済みで（ADR-021）、Renovate に移す利益より設定の作り直しの費用が大きいことである。
Dependabot は mise を扱えないので、この範囲は重ならない。

統合（Dependabot の廃止）に戻す条件は次のとおり。

- Dependabot が mise と `http:` backend を扱えるようになった。
- または、Renovate が、gradle、npm、github-actions、docker の更新でも Dependabot と同じ grouping と cooldown を、少ない設定で出せると確かめた。

## Consequences

### Positive

- mise の道具の版（Java と `http:jdtls`、`http:lombok` を除く）を、人が上げ忘れても PR が出る。
- Kiro CLI の新しい版が PR で分かる（lock の更新は人が行う）。
- zizmor の版を `mise.toml` と `zizmor.yml` で食い違わせない。

### Negative

- 更新の bot が 2 つになる。設定が 2 か所（`.github/dependabot.yml` と `renovate.jsonc`）に分かれる。
- GitHub App のインストールは管理者の操作で、設定を main に merge するまで動かない。
- Kiro CLI の PR は `mise.lock` を更新しないので、人が `mise lock` を実行して push するまで CI が落ちる。
- `mise.lock`、`.mise/locks/npm-iwe-org-iwe/<版>`、`mise.kiro-web.lock` が mise manager の PR で更新されることは、Renovate のソースの読みによる推論である。App が安全モード（`MISE_SAFE`）で `mise lock` を実行するときに更新されるかは、App を入れた後の最初の PR で確かめる。
- Java（`corretto-...`）、`http:jdtls`、`http:lombok` は、どちらの bot も追わない。手で上げる。
- 人が merge する PR が増える。mise の道具の PR は setup-mise を使うすべての workflow を動かす。

### Neutral

- 自動 merge を広げるときは、手戻りの検出と止め方を決める別の ADR を起こす。
- 設定を手元で検査する仕組み（参照元の `renovate.sh`）は、必要になったら足す。

## Alternatives Considered

### 選択肢1: Renovate に全エコシステムを移す

- **Description**：Dependabot を廃止し、gradle、npm、github-actions、docker も Renovate に任せる。
- **Pros**：bot が 1 つになる。
- **Cons**：ADR-021 の grouping と auto-merge の移行と、再検証が要る。今回の問題（mise の更新）の解決に要らない。

### 選択肢2: 手で上げ続ける

- **Description**：版を手で上げる。
- **Pros**：何も足さずに済む。
- **Cons**：上げ忘れが残る。

### 選択肢3: Renovate の自動 merge を使う

- **Description**：必須チェックが通った mise の PR を Renovate が merge する。
- **Pros**：人の作業が減る。
- **Cons**：検査器が壊れた更新を、成功と見分けられないまま取り込む。

### 選択肢4: 自分で動かす Renovate（workflow）

- **Description**：Renovate を GitHub Actions で動かす。
- **Pros**：App を入れずに済む。
- **Cons**：更新用の token か App の鍵を secret に持つ。hosted の App なら不要である。

## References

- [#389](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/389)
- [Renovate](../tooling/renovate.md)
- [ADR-021](ADR-021-group-dependabot-minor-and-patch-updates.md)
- [ADR-090](ADR-090-pin-cli-tools-with-mise-lock.md)
- [Renovate: mise manager](https://docs.renovatebot.com/modules/manager/mise/)
- [Renovate: minimumReleaseAge](https://docs.renovatebot.com/configuration-options/#minimumreleaseage)
