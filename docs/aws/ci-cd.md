---
type: Convention
title: AWSへのCI/CDの構成
description: AWSへデプロイするCI/CDのサービスの選び方、成果物と設定の置き場所、環境ごとのCIとCDのパイプラインの分け方、パイプラインで実行するテストの範囲を定める規約。AWSへのデプロイのワークフローを作るとき、CIに組み込むテストを決めるときに読む。
tags: [convention, aws, ci-cd, deployment, future-arch-guidelines]
---

# AWSへのCI/CDの構成

CI/CDは、ソースコードのリポジトリと一体のサービス（このリポジトリではGitHub Actions）を使い、JenkinsとCodeCommitを新しく採用しない。
本番とステージングはCIと分けたCDのパイプラインで手動の承認を挟んでデプロイし、開発環境はCIとCDを続けて実行してよい。
自動テストはパイプラインに組み込むことを基本にし、長時間かかる安定したテストに限り定期実行へ分ける。

## サービス

- リポジトリと一体のCI/CDのサービスを使う。GitHubならGitHub Actions、GitLabならGitLab CI/CDを使う。
- 運用の負荷が大きいため、Jenkinsは使わない。
- AWS CodeCommitは新しく採用しない。コンプライアンスの要件でデータの保管場所をAWSに限る場合に限り、ミラーやバックアップの用途で使ってよい。

## 成果物と設定の置き場所

- コンテナイメージはECRに置き、タグではなくdigestで後続の環境へ渡す（[ADR-020](../adr/ADR-020-sign-and-attest-container-images.md)）。
- デプロイ時に注入する設定と秘密は、SSM Parameter StoreかSecrets Managerに置く（[ADR-008](../adr/ADR-008-single-application-yaml-external-config.md)）。

## パイプラインの分け方

- 本番とステージングは、CIと分けたCDのパイプラインでデプロイし、デプロイの前に手動の承認を挟む。CIが一度だけ作った成果物を複数の環境へ昇格させるためにも、CIとCDを分ける。
- 開発環境は、`main`への変更を契機にCIからデプロイまで一つのパイプラインで続けて実行してよい。
- 環境ごとにパイプラインの構成が変わるため、環境の差分を把握して管理する。

ブランチの運用は[ADR-017](../adr/ADR-017-adopt-trunk-based-repository-governance.md)に、版とタグの作成は[リリース管理](../repository/release-management.md)に従う。
本番のCDの手順と前提は[本番CDワークフローのひな型](../../.github/workflows/production-cd.yml.example)を参照する。

## パイプラインで実行するテスト

- 自動化できるテストはすべて自動化し、パイプラインに組み込むことを第一の方針にする。デグレードの防止と品質を優先するためである。
- 実行に時間がかかるテストのうち、改修の頻度が低く品質が安定したものに限り、パイプラインから外して日次などの定期実行にする。

現在のCIで実行する検査は[Lintとテストのリファレンス](../tooling/lint-and-test.md)を参照する。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
