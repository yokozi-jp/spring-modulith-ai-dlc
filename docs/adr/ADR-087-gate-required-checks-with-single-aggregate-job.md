---
type: ADR
title: 'ADR-087: 必須チェックを集約ジョブ CI gate を中心にまとめる'
description: ruleset の必須チェックを集約ジョブ CI gate と 3 つの例外にまとめ、PR の検査を reusable workflow に分けて ci.yml から呼ぶ。
tags: [adr, ci, github-actions, governance]
---

# ADR-087: 必須チェックを集約ジョブ CI gate を中心にまとめる

## Status

Proposed

## Date

2026-11-17

## Context

ruleset `main` の必須チェックは、ジョブ名を 19 個登録している（2026-11-17 に `gh api repos/yokozi-jp/spring-modulith-ai-dlc/rulesets/23297515` で取得）。
ジョブ名を変えたり削除したりすると、ruleset も直さないと、その名前を待ち続けて merge が止まる。
ジョブを足したときに登録を忘れると、merge は止まらず、そのジョブが黙って必須から漏れる。
この 2 つの失敗を、登録する名前を減らすことで両方とも防ぎたい。

`on.pull_request` を持つ workflow は 21 本ある。
検査の動き方は 3 つの型に分かれる。

- **常時動く。** betterleaks、static-analysis（Semgrep など）、trivy、zizmor が該当する。
- **ジョブ単位で skip する。** backend-ci、frontend-ci、hadolint（Docker CI）、dast は、同じ workflow の `changes` ジョブの出力で、重いジョブを skip する。GitHub は skip を成功として扱う。
- **workflow ごと起動しない。** actionlint、compose-config、markdownlint、okf-validate、otel-collector、skills-check は `on.pull_request.paths` を使う。対象外の PR では check run が存在しないため、必須にできない。

集約ジョブは、同じ workflow のジョブの結果しか `needs` で読めない。
複数の workflow に分かれた検査を 1 つにまとめるには、workflow をまとめるか、結果を別の方式で集める必要がある。

GitHub Actions の仕様のうち、設計を左右するものが 3 つある。

- reusable workflow の中の `github` コンテキストは、呼び出し側のものである。トップレベルの `concurrency` が `github.workflow` を含むと、呼ばれた workflow の group がすべて `ci.yml` の名前になり、互いを取り消す。
- 呼ばれた側のジョブの check 名は `呼び出しジョブ名 / 呼ばれたジョブ名` になる。呼ばれる側の `pull_request` を外すと、旧い名前の check は報告されなくなる。
- 呼ばれた側が使える `GITHUB_TOKEN` の権限は、呼び出し側のジョブが与えた範囲までである。

[ADR-086](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/392) は、ruleset を `.github/rulesets/main.json` に置き、docs の必須チェックの一覧との一致を `tooling/ruleset-check` で検査する（PR #392 で提案中）。
この ADR は、その一覧の中身を減らす。

保つ性質は 3 つある。
docs だけの PR で backend の CI を走らせない。
並列度を落とさない。
`main` への push で動く CI を変えない。

## Decision

必須チェックを、`ci.yml` の集約ジョブ `CI gate` を中心にまとめる。

### 構成

- `ci.yml` を新設し、`on.pull_request`（types は既定の `opened`、`synchronize`、`reopened`）で、次の 14 本の workflow を `uses:` で呼ぶ。
  actionlint、betterleaks、backend-ci、compose-config、dast（passive のみ）、frontend-ci、hadolint、markdownlint、okf-validate、otel-collector、skills-check、static-analysis、trivy、zizmor。
- 各 workflow は `workflow_call` を足し、`push` などの既存のトリガーは残す。`main` への push で動く CI は変えない。
- 差分判定は、各 workflow の中の `changes` ジョブに残す。呼び出し側のジョブに `if` を付けず、skip は呼ばれた側のジョブの `if` で行う。この場合、呼び出しジョブの結果は `success` になる。
- `on.pull_request.paths` を使う 6 本（actionlint、compose-config、markdownlint、okf-validate、otel-collector、skills-check）は、`paths:` を外し、backend-ci と同じ `changes` ジョブとジョブ単位の `if` に直す。対象パスは現行の `paths:` と同じ glob で、判定は `tooling/ci-changes/changed.sh` が行う。この判定は、`ci.yml` の変更でも `true` にする。呼び出しの配線の誤りを、その PR で検出するためである。
- `changes` は差分の取得に失敗したとき、検査を実行する側へ倒す現行の挙動を保つ。
- 差分判定を `ci.yml` の 1 か所に集約することは、この ADR の範囲に含めない。`push` 側の `paths:` が残るため、パスの定義の重複は減らない。判定の仕組みだけを `changed.sh` に共有する。backend-ci、hadolint、frontend-ci、dast の既存の `changes` は、正規表現のまま残し、`changed.sh` へ寄せることは別の Issue で扱う。

### 集約ジョブ

- `CI gate` は、`ci.yml` のほかのすべてのジョブを `needs` に並べ、`if: always()` を付ける。
- 判定は `needs.*.result` がすべて `success` であることだけを求める。`skipped`、`failure`、`cancelled` は失敗にする。`changes` が失敗した workflow は、呼び出しジョブが `failure` になるので、gate を通らない。
- 判定は third-party の action を使わず、`toJSON(needs)` を `jq` で調べるシェルで書く。
- skip した検査が `success` になること（呼び出しジョブの結果が、中のジョブがすべて skip でも `success` になること）は、最初の PR で実際の PR を使って確かめる。`skipped` になる場合も、gate は失敗側に倒れるので、見逃しにはならない。

### concurrency と権限

- `ci.yml` の group は `ci-${{ github.ref }}`、`cancel-in-progress` は `true` にする。
- 呼ばれる workflow は、現行の `concurrency` の group の前に、ファイル名の stem と `-` を付ける（例：`backend-ci-${{ github.workflow }}-${{ github.ref }}`）。呼ばれると `github.workflow` が `CI` になり、単独の実行と `main` への push とは group が別になる。stem が違うので、呼ばれた workflow どうしも重ならない。取り消しの挙動は、3 つの起動のしかたのどれでも現行と同じである。
- 呼び出しジョブの `permissions` には、呼ばれた側の各ジョブが現在宣言している権限の全量を写す。呼ばれた側は、これを下げることしかできない。
- `ci.yml` のトップレベルは `permissions: {}` とする。

### 必須チェックと対象外

ruleset の必須チェックを、次の 4 つにする。

- `CI gate`：`ci.yml` の集約ジョブ。
- `Validate PR title`：`conventional-commits.yml` は PR タイトルの編集（`edited`）で再検査する。`ci.yml` の types に `edited` を足すと、タイトルを直すたびに全体が走り直す。
- `Detect API contract changes`：`api-contract.yml` は `api-breaking-approved` ラベルの付け外し（`labeled`、`unlabeled`）で再判定する（[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md)）。
- `Check API contract 🔀`：`Detect API contract changes` と同じ理由である。

ゲートの対象にしない workflow は、次のとおりである。

- conventional-commits、api-contract：上の 3 つのとおり、単独の必須チェックに残す。
- e2e：必須にしない（[ADR-057](ADR-057-adopt-playwright-for-e2e-tests.md)）。
- mutation-test：必須にしない。変更ごとに実行するが、merge の条件にしない（[ADR-076](ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
- codeql：PR でも動くが、現行どおり必須にしない。
- release-please：`validate` は release の設定の検査で、merge の条件にしない。
- dependabot-auto-merge：`contents: write` と `pull-requests: write` を持つため、呼ばない。
- container-publish：`workflow_dispatch` だけで動く。

この ADR で新たに必須になる検査は、14 本の workflow のジョブのうち、現在の ruleset にない名前のものである。
`Check duplicate code`、`Check Taskfile`、`Run task otel-collector-check 🔭`、`Run task skills-check 🧩`、`Run markdownlint-cli2 📝`、`Run task okf-check 📚`、`Run docker compose config 🐳` と actionlint のジョブが該当する。
実装の PR で、ruleset の取得結果と突き合わせて全量を docs に書く。
[ADR-021](ADR-021-group-dependabot-minor-and-patch-updates.md) が定めるとおり、auto-merge の安全性は必須チェックの集合が決める。この集合の中心が `CI gate` になる。

### 検査

`tooling/ci-gate-check/` に、次のことを確かめるスクリプトと fixture のテストを置く。
除外する workflow とその理由は `tooling/ci-gate-check/excluded.tsv` が正で、上の一覧はこの判断の時点の写しである。
スクリプトは `ci.yml` のジョブとして実行し、`CI gate` の `needs` に入れる。

- `ci.yml` に、check 名が `CI gate` の集約ジョブがあり、`if: always()` を持つ。
- `ci.yml` が workflow を呼ぶジョブの id が、呼ぶ workflow のファイル名の stem と同じである。
- `ci.yml` の `CI gate` 以外のすべてのジョブが、`CI gate` の `needs` にある。
- `.github/workflows/*.yml` のうち `on.pull_request` を持つものは、すべて `ci.yml` から呼ばれているか、除外の一覧に理由つきで載っている。
- 呼ばれる workflow のトップレベルの `concurrency` の group が、ファイル名の stem と `-` で始まる。

### ruleset の差し替え

check 名が変わるため、次の順にする。

1. `ci.yml` を足す PR を merge する。呼ばれる workflow は `pull_request` を残し、一時的に二重に走る。ruleset は変えない。
2. 次の PR で、`CI gate` の check が出ることを確認する。
3. ruleset に `CI gate` を追加し、個別のチェックと併存させる。
4. ruleset から個別の必須チェックを外し、`Validate PR title` と API contract の 2 つ、`CI gate` だけにする。
5. 呼ばれる workflow から `pull_request` を外す PR を merge する。
6. 開いている PR は、branch を更新して `CI gate` を出す。

PR は 3 つに分ける。
1 つ目は ci.yml の追加と、backend-ci、hadolint の移行（手順 1）。
2 つ目は残りの workflow の移行。
3 つ目は ruleset の差し替えと、`pull_request` の削除、docs の更新（手順 3 から 6）。

[ADR-017](ADR-017-adopt-trunk-based-repository-governance.md) の「常時必須チェックと条件付き品質ゲートを分けて管理する」方針は、この ADR が置き換える。
`docs/repository/branch-protection.md` の必須チェックの節と、ADR-086 の `tooling/ruleset-check` が見る一覧は、実装の PR で更新する。

実装の 1 つ目の PR で、`ci.yml` の取り消しは呼ばれた側だけに効き、呼ばれる側が `pull_request` でも単独で動く移行の間は、その実行が取り消されなくなることが分かった。そのため、group を `event_name` で分ける案をやめ、ファイル名の stem を前に付ける案に書き直した（2026-11-17）。

## Consequences

### Positive

- ジョブの追加や名前の変更で、ruleset を直さなくてよくなる。登録し忘れて検査が必須から漏れることも、名前を待ち続けて merge が止まることもなくなる。
- 新しい検査は、workflow を足して `ci.yml` から呼ぶだけで必須になる。足し忘れは `ci-gate-check` が検出する。
- actionlint、markdownlint、okf-check、compose-config も、対象の PR では必須になる。
- 判定が `success` だけなので、skip の扱いに抜け穴がない。

### Negative

- reusable workflow を呼ぶジョブには、`runs-on`、`steps`、`env` を書けない。
- 呼ばれる workflow が、`push` などと `workflow_call` の 2 つのモードを持ち続ける。
- 移行の間、呼ばれる workflow が二重に走る。
- check 名が `呼び出しジョブ名 / 呼ばれたジョブ名` の形になり、PR の画面で長く表示される。
- `CI gate` の判定が壊れると、Dependabot の PR まで auto-merge される。判定のシェルは fixture のテストで守る。
- 必須チェックは 1 つにならず、4 つ残る。タイトルとラベルの再判定は、`ci.yml` に入れると全体が走り直すためである。

### Neutral

- 差分判定の集約は、別の ADR の判断に残す。
- Trivy は docs だけの PR でも走る。path filter 化は別の Issue で判断する。
- merge queue を使う場合は、`ci.yml` に `merge_group` トリガーが必要になる。今は使わない。
- 移行の前後で、docs だけの PR と backend を変える PR について、全ジョブの完了までの時間を比べる。直前の 10 本の PR の中央値より 1 分以上遅ければ見直す。

## Alternatives Considered

### 選択肢1: workflow を直さず、ジョブ名の登録だけを減らす

- **Description**：`on.pull_request.paths` を使う 6 本だけをジョブ単位の skip に直し、ジョブ名で ruleset に登録する。名前のずれは ADR-086 の `ruleset-check` で検出する。
- **Pros**：変更が最も小さい。reusable workflow の制約を受けない。
- **Cons**：ジョブの名前を変えるたびに ruleset を直す手間は残る。登録の数も減らない。

### 選択肢2: ruleset を JSON でリポジトリに置き、名前の一致を検査する

- **Description**：ADR-086 が採用する方式である。
- **Pros**：docs と実物のずれを機械で検出できる。
- **Cons**：検出するだけで、ruleset を直す手間は残る。この ADR とは補い合う関係で、ADR-086 の検査が見る一覧を、この ADR が 4 つに減らす。

### 選択肢3: 別の workflow が Checks API を読んで待つ

- **Description**：`workflow_run` や polling で、同じ SHA のすべての check の結果を集める。
- **Pros**：既存の workflow を変えずに済む。名前の一覧を持たない作りにもできる。
- **Cons**：待つ間 runner を使う。ほかのツールが付ける check まで拾うため、除外の規則が要る。`workflow_run` は zizmor が警戒するトリガーである。

### 選択肢4: 1 つの `ci.yml` に全ジョブを直書きする

- **Description**：reusable workflow を使わず、14 本のジョブを 1 ファイルに書く。対象の workflow を合わせると約 1,500 行になる。
- **Pros**：concurrency の衝突、check 名の変化、2 つのモードが起きない。ジョブごとに `permissions` を宣言でき、トップレベルを `permissions: {}` にもできる。
- **Cons**：1 ファイルが肥大する。`main` への push で動く workflow との共通化が難しい。

### 選択肢5: `re-actors/alls-green` を使う

- **Description**：集約ジョブの判定を third-party の action に任せる。
- **Pros**：`allowed-skips` で skip を許すジョブを指定できる。
- **Cons**：依存が 1 つ増える。この ADR の判定は `success` だけを求めるので、数行のシェルで足りる。

## References

- [Reusing workflow configurations](https://docs.github.com/en/actions/reference/workflows-and-actions/reusable-workflows)
- [main ブランチの保護設定](../repository/branch-protection.md)
- [ADR-017](ADR-017-adopt-trunk-based-repository-governance.md)、[ADR-021](ADR-021-group-dependabot-minor-and-patch-updates.md)、[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md)、[ADR-057](ADR-057-adopt-playwright-for-e2e-tests.md)、[ADR-076](ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)
- Issue #252
