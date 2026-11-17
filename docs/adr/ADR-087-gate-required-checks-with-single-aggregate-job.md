---
type: ADR
title: 'ADR-087: 必須チェックを集約ジョブ CI gate の 1 つにまとめる'
description: ruleset の必須チェックを集約ジョブ CI gate の 1 つにし、PR の検査を reusable workflow に分けて ci.yml から呼ぶ。
tags: [adr, ci, github-actions, governance]
---

# ADR-087: 必須チェックを集約ジョブ CI gate の 1 つにまとめる

## Status

Proposed

## Date

2026-11-17

## Context

ruleset `main` の必須チェックは、ジョブ名を 17 個登録している。
ジョブ名を変えたりジョブを足したりするたびに ruleset も直す必要があり、直し忘れると merge が止まる。
[main ブランチの保護設定](../repository/branch-protection.md)の一覧は 19 個で、ruleset の実物と DAST の 2 件がずれている。

PR で動く workflow は約 18 本ある。
次の 3 つの型が混在する。

- 常時動く。betterleaks、Semgrep、Trivy、zizmor、commitlint が該当する。
- ジョブ単位で skip する。backend、frontend、docker、api-contract、DAST は `changes` ジョブの出力で重いジョブを skip する。GitHub は skip を成功として扱う。
- workflow ごと起動しない。actionlint、markdownlint、okf-validate、compose-config、otel-collector、skills-check、E2E は `on.pull_request.paths` を使う。対象外の PR では check run が存在しないため、必須にできない。

集約ジョブは同じ workflow のジョブの結果しか `needs` で読めない。
複数の workflow に分かれた検査を 1 つの必須チェックにするには、workflow をまとめるか、別の方式で結果を集める必要がある。
docs だけの PR で backend の CI を走らせない性質と、全体の所要時間は保つ。

## Decision

必須チェックを、`ci.yml` の集約ジョブ `CI gate` の 1 つにまとめる。

- `ci.yml` を新設し、PR で動く検査を `workflow_call` の reusable workflow として `uses:` で呼ぶ。既存の workflow ファイルは残し、`on:` に `workflow_call` を足す。
- 差分判定は `ci.yml` の `changes` ジョブ 1 つに集約する。対象パスは現行の各 workflow の定義を写し、旧と新で結果が一致することをテストで確認する。差分の取得に失敗したときに検査を実行する側へ倒す挙動を保つ。
- path filter を持つ検査は、workflow レベルの `paths:` をやめ、`changes` の出力によるジョブ単位の `if` で skip する。常時動く検査は `changes` に依存させない。
- `CI gate` は `if: always()` を付け、`needs.*.result` を判定する。常時動くジョブは `success` だけを許し、path filter を持つジョブは `success` と `skipped` を許す。`failure` と `cancelled` は失敗にする。判定は third-party の action を使わず、シェルで書く。
- ゲート以外のすべてのジョブが `needs` に入っていること、または除外の理由が書かれていることを、スクリプトで検査する。足し忘れを CI が検出する。
- reusable workflow の間に `needs` を張らない。workflow 内の依存は残す。
- 呼び出し側のジョブで `permissions` を宣言する。SARIF を送る Semgrep と Trivy には `security-events: write` を与える。
- `push`、`schedule`、`workflow_dispatch` で動く workflow（CodeQL、DAST の active scan、mutation、release-please、container-publish）と、必須にしない E2E は、ゲートの対象にしない。
- ruleset には `CI gate` だけを、`integration_id: 15368`（GitHub Actions）付きで登録する。
- ruleset は次の順で差し替える。
  1. `ci.yml` を merge する。ruleset はそのままにする。
  2. 次の PR で `CI gate` の check が出ることを確認する。
  3. ruleset に `CI gate` を追加し、個別の 17 個と併存させる。
  4. 個別の 17 個を外す。

実装は 3 つの PR に分ける。
最初に gate と backend、docker の移行、次に残りの workflow、最後に ruleset の差し替えを行う。

## Consequences

### Positive

- ジョブの追加や名前の変更で ruleset を直さなくてよくなる。直し忘れによる merge の停止がなくなる。
- 新しい検査は、reusable workflow を足して `needs` に 1 行足すだけで必須になる。
- actionlint、markdownlint、okf-check、compose-config も、対象の PR では必須になる。
- 差分判定のスクリプトが 1 か所になり、path の定義が重複しない。
- `needs` の足し忘れを CI が検出する。

### Negative

- reusable workflow の呼び出しジョブでは、`runs-on`、`steps`、`env` を書けない。`permissions` は呼び出し側で宣言し、呼ばれた側は下げることしかできない。
- 1 つの workflow ファイルから呼べる reusable workflow は一意で 50 個までである。現在は約 18 本である。
- check 名が `呼び出しジョブ名 / 呼ばれたジョブ名` の形になり、PR の画面で長く表示される。
- 既存の workflow すべてを書き換えるため、移行の PR が大きい。

### Neutral

- merge queue を使う場合は、`ci.yml` に `merge_group` トリガーが必要になる。今は使わないので足さない。
- Trivy は docs だけの PR でも走る。path filter 化は別の Issue で判断する。
- ruleset を JSON でリポジトリに置く判断は、今回しない。
- 移行の前後で、PR の所要時間と runner の使用分数を比べる。悪化していれば見直す。

## Alternatives Considered

### 選択肢1: 別の workflow が Checks API を読んで待つ

- **Description**：`workflow_run` や polling で、他の workflow の check の結果を集める。
- **Pros**：既存の workflow を変えずに済む。
- **Cons**：待つ間 runner を使う。期待する check の一覧を別の場所に持つため、17 個の名前が移るだけで保守の負担が残る。`workflow_run` は zizmor が警戒するトリガーである。

### 選択肢2: ruleset を JSON でリポジトリに置き、名前の一致を検査する

- **Description**：ruleset を `.github/rulesets/` に置き、ジョブ名との不一致を CI で検出する。
- **Pros**：変更が小さい。docs と実物のずれを防げる。
- **Cons**：不一致を検知するだけで、ruleset を直す手間は残る。適用は手動のままである。

### 選択肢3: 1 つの `ci.yml` に全ジョブを直書きする

- **Description**：reusable workflow を使わず、すべてのジョブを 1 ファイルに書く。
- **Pros**：呼び出しジョブの制約がない。
- **Cons**：ファイルが肥大する。ジョブごとの `permissions` を最小にしにくく、最も広い側に揃えがちになる。

### 選択肢4: `re-actors/alls-green` を使う

- **Description**：集約ジョブの判定を third-party の action に任せる。
- **Pros**：`allowed-skips` で skip を許すジョブを指定できる。
- **Cons**：依存が 1 つ増える。判定は数行のシェルで書ける。

## References

- [Reusing workflow configurations](https://docs.github.com/en/actions/reference/workflows-and-actions/reusable-workflows)
- [main ブランチの保護設定](../repository/branch-protection.md)
- Issue #252
