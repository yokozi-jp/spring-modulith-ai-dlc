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

次のジョブはすべての Pull Request で check run を報告するため、ruleset の required status checks に正確な名前で登録する。

- `Run betterleaks 🔐`
- `Run Semgrep 🔎`
- `Trivy scan (backend) 🛡️`
- `Trivy scan (frontend) 🛡️`
- `Run zizmor 🌈`
- `Validate PR title`
- `Detect backend changes`
- `Lint (Spotless + PMD + SpotBugs) ☕`
- `Test & Coverage ☕`
- `Detect frontend changes`
- `Verify frontend`
- `Detect docker changes`
- `Run hadolint 🐳`
- `Run docker build --check 🐳`
- `Build and test backend image 🐳`
- `Detect DAST changes`
- `Run ZAP passive scan 🕷️`
- `Detect API contract changes`
- `Check API contract 🔀`

Backend、frontend、Docker、DAST の workflow は Pull Request ごとに変更対象を検出する。

関連パスを変更していない場合、重い検査ジョブはジョブ単位で `skipped` を報告し、GitHub は required status check を満たしたものとして扱う。

変更検知ジョブ、hadolint、`docker build --check` も必須にする。

`Check API contract 🔀` は、`api-breaking-approved` ラベルの付け外しでも再判定する（[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)）。

稼働中の ruleset への追加はリポジトリの所有者が行い、上の一覧と同じ名前で登録する。

`Publish API docs 📘` が設計書を公開するには、所有者が GitHub Pages を有効にし、公開元を GitHub Actions にする。
GitHub Pagesでは通常、APIの設計内容がすべて公開されるため、有効化する前にリポジトリのPages公開範囲を確認する。

検知ジョブやイメージビルドの前段が失敗した場合、後続ジョブが `skipped` になっても前段の失敗によってマージを止めるためである。

Semgrep、Trivy、ZAP は現在、検出結果を SARIF へ送ってもジョブ自体を失敗させない（ZAP は [ADR-056](../adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。

`Run ZAP active scan 🕷️` は schedule と手動実行のときだけ動くので、必須チェックにしない。

この必須設定が保証するのはスキャンの完走であり、検出がゼロであることではない。

Security タブの未解決結果はレビュー時に確認し、受容する場合は理由を記録する。

## 変更対象に応じて必須となるチェック

次のジョブは workflow レベルの path filter を持つため、該当ファイルを変更した Pull Request で実行結果を確認する。

- GitHub Actions：`actionlint`
- Markdown：`Run markdownlint-cli2 📝`
- docs、steering、OKF設定、Taskfile、ruleset、workflow、`tooling/ruleset-check/`：`Run task okf-check 📚`
- Compose：`Run docker compose config 🐳`

path filter によって workflow 自体が作られない Pull Request では、これらの check run も存在しない。

そのまま全 Pull Request の required status checks に登録すると、対象外の変更が待機状態のまま merge できなくなるため、常時必須の一覧には加えない。

## 必須にしないチェック

E2E の `Run E2E tests 🎭` は path filter を持つが、required status checks に登録しない。
実ブラウザと複数のコンテナを使うため、変更と関係のない原因でも失敗しうる（[ADR-057](../adr/ADR-057-adopt-playwright-for-e2e-tests.md)）。
失敗したときは、レビューで trace と backend のログを確かめて merge を判断する。

## その他の推奨設定

GitHub の General、Pull Requests で次を設定する。

- Automatically delete head branches を有効にする。
- Allow auto-merge は、必須チェックを維持できる場合だけ有効にする。

Private Vulnerability Reporting は Security、Code security and analysis で有効にする。

リリース自動化に必要な secret と権限は [release-management.md](release-management.md) に記載する。
