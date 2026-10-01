---
type: Convention
title: main ブランチの保護設定
description: 既定ブランチ main を対象にした GitHub branch ruleset の保護設定。
tags: [convention, operations, governance, git, github]
---

# main ブランチの保護設定

## 適用方法

GitHub の Rules、Rulesets で branch ruleset を作り、既定ブランチ `main` を対象にする。

ruleset は Active にし、管理者を含む bypass は常設しない。

緊急時に bypass を使った場合は、理由と後続のレビュー結果を Issue または Pull Request に残す。

## 必須ルール

次のルールを有効にする。

- branch deletion を禁止する。
- non-fast-forward push を禁止する。
- Pull Request を必須にする。
- linear history を必須にする。
- merge 前にすべての会話の解決を必須にする。
- merge 前にブランチを最新の `main` へ更新する。
- 一件以上の承認を必須にする。
- 新しい commit が追加されたら古い承認を取り消す。
- CODEOWNERS の承認を必須にする。

squash merge だけを有効にし、merge commit と rebase merge は無効にする。

squash commit の既定メッセージには Pull Request のタイトルを使う。

## レビュー担当者の前提

現在リポジトリ内で有効性を確認できる所有者は `@yokozi-jp` だけである。

Pull Request の作成者は自分の変更を承認できないため、コード所有者レビューを必須にする前に、各領域へ作成者以外の実在するレビュー担当者を一名以上追加する。

チームを作成した場合は `.github/CODEOWNERS` の backend、frontend、infrastructure、`.kiro` の規則を実在する `@owner/team` へ置き換える。

存在しないチーム名を先に指定するとレビュー要求が機能しないため、仮の team slug は記載しない。

## 常時必須にするステータスチェック

次のジョブはすべての Pull Request で check run を報告するため、ruleset の required status checks に正確な名前で登録する。

- `Run betterleaks 🔐`
- `Run Semgrep 🔎`
- `Trivy scan (frontend) 🛡️`
- `Trivy scan (backend) 🛡️`
- `Run zizmor 🌈`
- `Validate PR title`
- `Detect frontend changes`
- `Verify frontend`
- `Detect backend changes`
- `Detect docker changes`
- `Lint (Spotless + PMD + SpotBugs) ☕`
- `Test & Coverage ☕`
- `Run hadolint 🐳`
- `Run docker build --check 🐳`
- `Build and test backend image 🐳`

Frontend、backend、Docker の workflow は Pull Request ごとに変更対象を検出する。

関連パスを変更していない場合、重い検査ジョブはジョブ単位で `skipped` を報告し、GitHub は required status check を満たしたものとして扱う。

変更検知ジョブ、hadolint、`docker build --check` も必須にする。

検知ジョブやイメージビルドの前段が失敗した場合、後続ジョブが `skipped` になっても前段の失敗によってマージを止めるためである。

Semgrep と Trivy は現在、検出結果を SARIF へ送ってもジョブ自体を失敗させない。

この必須設定が保証するのはスキャンの完走であり、検出がゼロであることではない。

Security タブの未解決結果はレビュー時に確認し、受容する場合は理由を記録する。

## 変更対象に応じて必須となるチェック

次のジョブは workflow レベルの path filter を持つため、該当ファイルを変更した Pull Request で実行結果を確認する。

- GitHub Actions：`actionlint`
- Markdown：`Run markdownlint-cli2 📝`
- docs、steering、OKF設定、Taskfile：`Run task okf-check 📚`
- Compose：`Run docker compose config 🐳`

path filter によって workflow 自体が作られない Pull Request では、これらの check run も存在しない。

そのまま全 Pull Request の required status checks に登録すると、対象外の変更が待機状態のまま merge できなくなるため、常時必須の一覧には加えない。

## その他の推奨設定

GitHub の General、Pull Requests で次を設定する。

- Automatically delete head branches を有効にする。
- Always suggest updating pull request branches を有効にする。
- Allow auto-merge は、必須レビューとチェックを維持できる場合だけ有効にする。

Private Vulnerability Reporting は Security、Code security and analysis で有効にする。

リリース自動化に必要な secret と権限は [release-management.md](release-management.md) に記載する。
