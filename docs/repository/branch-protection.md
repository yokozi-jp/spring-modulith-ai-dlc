---
type: Convention
title: main ブランチの保護設定
description: 既定ブランチ main を対象にした GitHub branch ruleset の保護設定。
tags: [convention, operations, governance, git, github]
---

# main ブランチの保護設定

## 適用方法

GitHub の Rules、Rulesets で branch ruleset を作り、既定ブランチ `main` を対象にする。

ruleset の正本は `.github/rulesets/main.json` である。
変更するときは、このファイルを直し、次のコマンドで GitHub へ反映する。

```bash
gh api -X PUT repos/yokozi-jp/spring-modulith-ai-dlc/rulesets/23297515 --input .github/rulesets/main.json
```

必須チェックの名前は、このファイル、下の一覧、workflow の job 名の 3 か所で、絵文字の異体字選択子（U+FE0F）まで一致させる。
一致しないチェックは、報告されないまま待機になり、merge できなくなる。
`task okf-check` が、この 3 か所の一致を検査する。GitHub 上の ruleset との一致は検査しない。理由は [ADR-086](../adr/ADR-086-align-main-protection-with-solo-and-agent-operation.md) にある。

ruleset は Active にする。
bypass できるのは、リポジトリの管理者（RepositoryRole の id `5`）だけで、`bypass_mode` は `pull_request` にする。
`pull_request` モードの bypass は Pull Request の merge にだけ効くため、`main` への直接 push は管理者でもできない。

管理者が bypass で merge したときは、理由を Pull Request に残す。
エージェントは bypass を使わない（[Draftからマージまで](../pull-request/review-request-and-merge.md)）。

## 必須ルール

次のルールを有効にする。

- branch deletion を禁止する。
- non-fast-forward push を禁止する。
- Pull Request を必須にする。
- 必須ステータスチェックの成功を必須にする。

次の設定は要求しない（[ADR-086](../adr/ADR-086-align-main-protection-with-solo-and-agent-operation.md)）。

- 承認の数を 0 にし、承認とコード所有者の承認を要求しない。
- merge 前の会話の解決を要求しない。bot のコメントも resolve しなくてよい。
- merge 前にブランチを最新の `main` へ更新することを要求しない（`strict_required_status_checks_policy` は `false`）。
- linear history を要求しない。

squash merge だけを有効にし、merge commit と rebase merge は無効にする。

squash commit の既定メッセージには Pull Request のタイトルを使い、本文には Pull Request の本文を使う（`squash_merge_commit_message` は `PR_BODY`）。

人が必ず読む Pull Request は[Draftからマージまで](../pull-request/review-request-and-merge.md)に従う。

## CODEOWNERS

`.github/CODEOWNERS` を置く。
ruleset ではコード所有者の承認を要求しない。
所有者は `@yokozi-jp` だけであり、存在しないチーム名は指定しない。
チームを作ったときは、`@owner/team` の実在を確かめてから置き換える。

## 常時必須にするステータスチェック

次の 4 つを、ruleset の required status checks に正確な名前で登録する（[ADR-087](../adr/ADR-087-gate-required-checks-with-single-aggregate-job.md)）。

- `CI gate`
- `Validate PR title`
- `Detect API contract changes`
- `Check API contract 🔀`

`CI gate` は `ci.yml` の集約ジョブで、`ci.yml` が呼ぶすべての検査の結果を 1 つにまとめる。
検査を足すときは、workflow を足して `ci.yml` から呼び、`CI gate` の `needs` にも足す。ruleset は変えない。
足し忘れは、`task ci-gate-check` が検出する。
`CI gate` が待つ検査の一覧は `ci.yml` が正で、この文書に写さない。

`Validate PR title` と API contract の 2 つは、`ci.yml` に入れない。
`Validate PR title` は PR タイトルの編集で、API contract は `api-breaking-approved` ラベルの付け外しで再判定する（[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)）。
`ci.yml` に入れると、タイトルやラベルを変えるたびに全体が走り直す。

`CI gate` は、呼んだ検査がすべて `success` のときだけ成功にする。
`failure`、`cancelled`、`skipped` は失敗にする。
関連パスを変更していない PR では、各 workflow の `changes` ジョブが対象外と判定し、重い検査ジョブをジョブ単位で skip する。
このとき呼び出しジョブの結果は `success` なので、`CI gate` は通る。
`changes` が失敗した検査は、呼び出しジョブが失敗するので、`CI gate` を通らない。

対象のパスは `tooling/ci-changes/changed.sh` が判定する。`ci.yml` の変更では、すべての検査を実行する。

稼働中の ruleset の変更はリポジトリの所有者が行い、上の一覧と同じ名前で登録する。

`Publish API docs 📘` が設計書を公開するには、所有者が GitHub Pages を有効にし、公開元を GitHub Actions にする。
GitHub Pagesでは通常、APIの設計内容がすべて公開されるため、有効化する前にリポジトリのPages公開範囲を確認する。

Semgrep、Trivy、ZAP は現在、検出結果を SARIF へ送ってもジョブ自体を失敗させない（ZAP は [ADR-056](../adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。

`Run ZAP active scan 🕷` は schedule と手動実行のときだけ動くので、必須チェックにしない。

この必須設定が保証するのはスキャンの完走であり、検出がゼロであることではない。

Security タブの未解決結果はレビュー時に確認し、受容する場合は理由を記録する。

## 必須にしないチェック

E2E の `Run E2E tests 🎭` は path filter を持つが、`ci.yml` から呼ばず、required status checks に登録しない。
実ブラウザと複数のコンテナを使うため、変更と関係のない原因でも失敗しうる（[ADR-057](../adr/ADR-057-adopt-playwright-for-e2e-tests.md)）。
失敗したときは、レビューで trace と backend のログを確かめて merge を判断する。

## その他の推奨設定

GitHub の General、Pull Requests で次を設定する。

- Automatically delete head branches を有効にする。
- Allow auto-merge は、必須チェックを維持できる場合だけ有効にする。

Private Vulnerability Reporting は Security、Code security and analysis で有効にする。

リリース自動化に必要な secret と権限は [release-management.md](release-management.md) に記載する。
