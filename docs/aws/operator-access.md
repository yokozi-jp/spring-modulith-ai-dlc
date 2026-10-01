---
type: Convention
title: 内部リソースへの運用者の接続
description: RDSなどVPC内のリソースへ運用者が手作業で接続するときの方式、使わない方式、操作ログの扱いを定める規約。踏み台の構成を決めるとき、DBのデータ調査やデータパッチの接続経路を用意するときに読む。
tags: [convention, aws, security, operations, future-arch-guidelines]
---

# 内部リソースへの運用者の接続

VPC内のリソースへ運用者が接続するときは、Systems ManagerのSession Managerを使う。
インバウンドのポートを開けたEC2の踏み台サーバーとSSHの鍵は使わない。
セッションログを監査証跡として保存する。

## 用語

**踏み台サーバー**：RDSのような内部のリソースへの手作業の接続を仲介し、重大な操作の監査証跡を残すための中継用のサーバー。

踏み台を使う作業の例は、curlなどによる疎通の確認、DBのデータ調査とデータパッチ、IaCの操作である。

## 方式

- Session Managerを使う。IAMで利用者ごとにアクセスを制御でき、インバウンドのポートとSSHの鍵が要らず、実行したコマンドを含むセッションログを標準で取れ、RDSへのポートフォワードもできるためである。
- パブリックサブネットに置いてSSHのポートを開けたEC2の踏み台サーバーは使わない。アクセスの制御がOSと鍵に頼り、OSのパッチと監視が要り、セッションログもOSで設定する必要があるためである。
- EC2 Instance Connect Endpointは第一候補にしない。RDSへの直接のポートフォワードがなく、実行したコマンドのログをOSで別に取る必要があるためである。

## 操作ログ

- セッションログはCloudWatch LogsかS3へ保存する。
- 保持期間は、監査ログとして[情報の機密性の分類と取り扱い要件](data-classification.md)に従って決める。
- DBへ接続するユーザーは[PostgreSQLのロールと監査](../database/postgresql-roles-and-audit.md)に従って払い出し、セッションログとDBの監査ログを突き合わせて作業者を特定できるようにする。

## 出典

- フューチャー株式会社「AWS設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forAWS/aws_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
