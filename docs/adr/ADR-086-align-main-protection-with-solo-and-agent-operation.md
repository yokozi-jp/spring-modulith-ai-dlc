---
type: ADR
title: 'ADR-086: main の保護を 1 人とエージェントの運用に合わせ、人が読む Pull Request を条件で決める'
description: 承認、会話の解決、最新の main への更新、linear history の要求を外し、管理者に Pull Request の merge だけの bypass を許す。人が必ず読む Pull Request は条件で決める。
tags: [adr, governance, github, code-review]
---

# ADR-086: main の保護を 1 人とエージェントの運用に合わせ、人が読む Pull Request を条件で決める

## Status

Proposed

## Date

2026-11-17

## Context

[ADR-017](ADR-017-adopt-trunk-based-repository-governance.md) は、一件以上の承認、コード所有者の承認、会話の解決、linear history を要求する方針だった。
実際の ruleset `main` は、承認の数が 0 で、コード所有者の承認も要求していない。
所有者は `@yokozi-jp` の 1 人で、Pull Request の作成者は自分の変更を承認できないため、承認を要求すると merge できなくなる。

ruleset には、運用を止める設定が 3 つあった。

- `strict_required_status_checks_policy` が `true` で、main が進むたびに Update branch と必須チェックのやり直しが要る。Dependabot の Pull Request が溜まると 1 本ずつしか merge できない。
- `required_review_thread_resolution` が `true` で、bot のコメントにも resolve が要る。
- `bypass_actors` が空で、チェックが壊れたときは ruleset を無効にしないと merge できない。

docs は、存在しない承認を前提にしていた。
エージェントは、その docs を読んで再承認の依頼や Reviewers の設定をしようとした。

[ADR-021](ADR-021-group-dependabot-minor-and-patch-updates.md) は、strict を外す案を、承認から merge までに `main` が動いた組み合わせを検証できないことを理由に却下した。
その後、Dependabot の Pull Request が溜まって 1 本ずつしか merge できず、#242 は merge まで 5 日かかった。この費用が、ADR-021 の却下の理由を上回ると判断する。

姉妹リポジトリ agent-software-factory の ruleset `protect-main` は、会話の解決を必須にせず、管理者に `bypass_mode: pull_request` だけを許している。

## Decision

ruleset `main` を次の設定にする。

- `strict_required_status_checks_policy` を `false` にする。ADR-021 の Alternatives Considered の却下を、この点で改める。
- `required_review_thread_resolution` を `false` にする。
- `required_linear_history` の規則を外す。merge の方式は squash だけなので、履歴は直線のままになる。
- `bypass_actors` に管理者（RepositoryRole の id `5`）を `bypass_mode: pull_request` で加える。`main` への直接 push は bypass でもできない。
- 承認の数は 0 のままにする。
- ruleset の正本を `.github/rulesets/main.json` に置き、必須チェックの名前を docs の一覧と workflow の名前に突き合わせる検査を `task okf-check` に入れる。GitHub への反映は手動の `gh api` で行う。

承認を要求しない代わりに、人が必ず読む Pull Request を条件で決める。
条件は、戻しにくい変更、エージェントの設定の変更、CI と hook のゲートの変更、認証と認可の変更、依存の追加、テストの弱体化である。
条件に当たる Pull Request は、エージェントが merge せず、`--admin` と auto-merge の有効化も人だけが行う。
条件の本文は [Draftからマージまで](../pull-request/review-request-and-merge.md) に置く。

ADR-017 の Decision のうち、承認、コード所有者の承認、会話の解決、linear history を要求する部分は、この ADR が置き換える。
ADR-017 と ADR-021 は Proposed なので、それぞれの Decision と Alternatives Considered を、この ADR を指す形に書き直した。

## Consequences

### Positive

- main が進んでも、必須チェックが通った Pull Request をそのまま merge できる。
- bot のコメントを resolve しなくても merge できる。
- ruleset をファイルで管理するため、変更の履歴と差分のレビューが残る。
- チェックが壊れたときも、ruleset を無効にせずに管理者が merge できる。
- docs とエージェントの手順が、実際の ruleset と一致する。

### Negative

- 最新の main と統合した結果を検査せずに merge するため、2 つの Pull Request が個別には通っても、合わせると壊れることがある。`main` への push で動く CI で検知し、赤くなったら管理者が直す。
- 承認がないので、人が読む Pull Request を守るかは運用に任せる。エージェントが管理者のアカウントで動くため、`--admin` を使わない規約を機械では止められない。条件を機械で検査する仕組みはない。
- ファイルを直しても GitHub に反映し忘れる、または UI で直接変えると、ファイルと実際の ruleset がずれる。この検査は検出しない。
- 管理者は bypass で必須チェックを飛ばして merge できる。理由を Pull Request に残す運用で補う。

### Neutral

- CODEOWNERS は、GitHub がレビューを自動で依頼するために残す。
- チームが増えたら、承認の数と CODEOWNERS を見直す。

## Alternatives Considered

### 承認の数を 1 にし、エージェントが承認する

- **Description**：エージェントや bot が承認する。
- **Pros**：ruleset に承認の要件が残る。
- **Cons**：承認が実質のレビューにならず、ゲートの見かけだけが増える。

### strict を残し、auto-merge と merge queue で解消する

- **Description**：最新化の必須を残し、merge queue で自動に追従させる。
- **Pros**：統合した結果を検査してから merge できる。
- **Cons**：merge queue の導入と CI の設定が要り、現在の規模に見合わない。

### CI で GitHub 上の ruleset とファイルを比べる

- **Description**：`gh api` で取得した ruleset とファイルの差を CI で検査する。
- **Pros**：反映し忘れと UI での変更を検出できる。
- **Cons**：読み取りの権限と通信が CI に要る。現在の規模では、先にファイルと docs と workflow の名前の一致を守る。

### ruleset を変えず、docs だけ直す

- **Description**：docs を ruleset に合わせ、strict と会話の解決の必須は残す。
- **Pros**：設定の変更が要らない。
- **Cons**：Dependabot の滞留と、チェックが壊れたときの merge 不能が残る。

## References

- [Draftからマージまで](../pull-request/review-request-and-merge.md)
- [ADR-021](ADR-021-group-dependabot-minor-and-patch-updates.md)
- [main ブランチの保護設定](../repository/branch-protection.md)
- [ADR-017](ADR-017-adopt-trunk-based-repository-governance.md)
- [Issue #248](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/248)、[#249](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/249)、[#250](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/250)
